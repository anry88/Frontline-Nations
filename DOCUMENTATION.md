# Frontline Nations Architecture

This document describes the command-only MVP architecture and separates it from the broader target defined by the product specification.

## Architecture Goal

The first version should be a modular monolith that proves the core product loop before the team invests in microservices, a global geopolitical map, complex diplomacy, or large catalogs of licensed equipment.

The deployed MVP has two runtime surfaces:

- Telegram Bot commands, callback queries, and inline alliance-selection buttons
- Spring Boot backend as the authority for gameplay and economy

PostgreSQL stores players and their explicit locale/nickname settings, command capacity and its upgrade audit, processed Telegram updates, owned personal units, three combat-group presets, equipment audit records, personal battles and their immutable group/map/opponent/tier snapshots, ordered spatial event JSON, campaign weeks and matchups, cumulative country ratings, contributions, weekly rewards, notification outbox entries, and wallet ledger entries. Scheduled jobs restore daily Combat Orders and drive the weekly campaign. Mini App, graphical replay visualization, equipment modules, branching technologies, and typed campaign assets remain future milestones.

### Identity, locale, and alliance selection

1. A new account maps Telegram's optional IETF `language_code` to one of eight supported locales, falling back to English. Existing accounts migrated from the Russian-only build retain Russian.
2. `/language` changes the persisted preference; later Telegram updates refresh only the locale hint and never overwrite that explicit choice.
3. `/nickname` normalizes whitespace, removes bidi/control characters, masks configured profanity, limits the result to 30 Unicode code points, and stores it as pending until the same player confirms the callback.
4. The versioned catalog contains all 249 ISO 3166-1 alpha-2 countries and territories plus explicit `XK`. Java CLDR provides localized display names, with explicit neutral names for Kosovo and Palestine.
5. `/country` offers language-relevant suggestions, locale-aware pages of ten, and accent-insensitive search across every supported translation and the two-letter code. Alliance selection remains immutable until seasonal switching rules are implemented.

## Core Data Flow

### Personal operation

1. The server deterministically generates five operation offers from a catalog of 24 battlefields for the player's current daily-order state.
2. The bot shows a named battlefield, biome, risk/reward tier, and tier-dependent intelligence for each offer.
3. The player maintains one of three reusable presets through `/army`. The persisted command-capacity budget grows from 10 to 1,000 CP through level-gated Research Point purchases, making heavy armor, artillery, aircraft, air defense, and reconnaissance compete for space at every echelon.
4. The selected operation resolves to a versioned 9×12 axial sector map. The bot sends its immutable square PNG assembled from generated hex terrain and objective blocks; coordinates, entries, roads, and objectives match the server map snapshot.
5. The player binds the active group to an entry and a first objective, then chooses a behavior doctrine. Doctrines change route preference, holding behavior, movement order, and target selection; engine v5 applies no hidden tactic, counter, or terrain power percentage.
6. The backend binds callbacks to the current order count and group version, rejects stale changes, derives a protected seed, and moves units through logical steps. Movement costs, line of sight, spotting, weapon range, minimum artillery range, cover, damage, and objective control are resolved using integer arithmetic.
7. Ground units capture an uncontested objective after its configured number of consecutive steps. Existing control remains until an opponent completes the same process, so defenders may contest or retake it. Aircraft do not capture objectives.
8. The battle ends when one side holds all important objectives, one army has no combat-capable units, or the 48-step safety boundary routes the weaker remaining army by objective control and hit points.
9. One transaction consumes the Combat Order and records the result, rewards, statistics, force tier and both deployed CP totals, operation metadata, engine/map version, route orders, full player/opponent/map snapshots, final objective state, and typed spatial events.
10. The bot presents objective control and selected highlights. A graphical replay UI remains planned; it will consume the stored spatial events rather than recalculate combat.

### Personal equipment

1. A one-time idempotent grant creates three presets and the 10 CP starter force from the source specification.
2. `/shop` reads the versioned JSON catalog and offers purchase for Credits or crafting for Credits plus Materials. Commander level gates later classes. Each class also declares movement profile/points, sight, finite minimum/maximum range, and fire mode.
3. `/upgrade` spends both resources and scales all base stats by a deterministic integer 12% per level through level 5.
4. Player balances, wallet ledger rows, owned-unit state, and equipment audit rows change in one transaction.
5. Generated fictional class icons are served by the backend and sent as Telegram equipment cards. Personal units are persistent and never consumed by the weekly campaign.
6. `/development` spends ledger-backed Research Points on six level-gated command-capacity ceilings: 25, 50, 100, 250, 500, and 1,000 CP. `/shop` supports batches of 1, 5, or 25 units, `/upgrade` groups identical equipment and upgrades 1, 5, or 25 at once, and `/army` adds or removes one available unit of a selected class per callback.
7. Battle category follows deployed CP rather than account level. Rewards scale by the category's configured multiplier, and the generated opponent remains inside the same category.
8. To keep map complexity bounded, identical units are snapshotted into formations keyed by class and upgrade level. Formation quantity scales hit points and outgoing damage; movement, range, terrain access, spotting, targeting, and capture eligibility still follow the equipment definition.

### Weekly campaign

1. A Monday 00:05 Belgrade job includes all 250 catalog countries and territories. Countries are ordered by cumulative rating descending and English name ascending, then paired adjacently. Therefore the initial zero-rating round is strictly English alphabetical: 1–2, 3–4, and so on through 125 matches.
2. Every country receives a seed-derived random NPC equipment group that totals 10–25 CP. Players use `/contribute` to add or replace a snapshot of their active personal group. The snapshot is immutable for resolution and does not remove equipment or spend Credits.
3. Contributions lock at Sunday 15:00 in `Europe/Belgrade`.
4. The aggregate engine combines the NPC composition and player equipment snapshots, preserving equipment class and upgrade level when it deploys armor, artillery, reconnaissance, air, and support formations.
5. One of ten 15×21 versioned weekly maps supplies terrain, three entries per side, and five capture points. `/front` sends the pre-rendered map image. Movement, finite range, direct-fire line of sight, indirect artillery, cover, capture, loss, and recapture resolve deterministically for at most 96 turns.
6. Current objective ownership scores by capture time; losing an objective removes its prior score. Destroyed enemy power and a configured fraction of allied surviving power complete the battle score. All-objective control and army destruction end early. At timeout, remaining power decides first, followed by objective and total score tie-breaks.
7. Both countries add their battle score to cumulative rating, so losing a single battle never wipes prior standing. The next week reorders all countries from that rating.
8. One transaction stores map/input/result snapshots, score components, rating transitions and typed events, then issues one ledger-backed reward per contributor. A durable notification outbox is delivered after commit and retried independently.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `telegram-adapter` | Telegram webhook, commands, callback queries, Mini App authentication |
| `player` | Account, profile, alliance membership, commander level, rating |
| `catalog` | Alliances, locations, units, modules, doctrines, balance configuration |
| `inventory` | Owned units, equipment, presets, repair and production state |
| `progression` | XP level gates, Research Point capacity upgrades, force tiers, future research nodes and prestige |
| `matchmaking` | Operation offers, opponent snapshots, difficulty bands |
| `battle-engine` | Versioned deterministic personal and aggregate simulations |
| `campaign` | Weekly matchups, campaign assets, deficits, contributions, rewards |
| `replay` | Ordered `BattleEvent` storage and delivery |
| `jobs` | Daily reset, snapshot refresh, campaign lifecycle, cleanup, rendering |
| `admin` | Protected catalog, balance, campaign, and operational controls |

Modules may live in one deployable Spring Boot application while keeping dependencies explicit. Domain modules should not call Telegram or rendering code directly; they publish results that adapters deliver.

## Battle Engine Contract

Every completed battle should retain:

- battle type and engine version
- seed hash and, after completion where appropriate, verification material
- immutable player/opponent or formation inputs
- relevant balance and battlefield configuration version
- result summary
- ordered events with logical ticks and typed payloads

The current personal engine contract is version 5. After resolution it stores the battle seed and hash, commander-level snapshot, full player and generated opponent groups, map/version snapshot, group version, selected location/biome/difficulty/enemy archetype, deployment entry, first objective, both behavior doctrines, final objective control, typed movement/fire/capture events, end reason, and rewards. Operation offers are bound to the player, game date, current order count, and preset version; an old inline button cannot consume a newer order or silently use a changed group. Older engine calculations remain isolated only for historical replay compatibility.

The same engine version, seed, input snapshot, and configuration must reproduce the same outcome and event order. Replay clients may interpolate animations, but they may not invent gameplay outcomes.

## Persistence Baseline

The specification proposes PostgreSQL tables for players, alliances, battlefields, catalogs, owned units, modules, research, doctrines, combat groups, operations, battles, events, campaigns, contributions, results, and wallet transactions.

Implementation should refine this model through versioned migrations. Important invariants include:

- one Telegram identity maps to one player account unless an explicit account-linking flow is introduced
- resource balances change only through ledger-backed transactions
- one-time starter grants and equipment transactions are idempotent and auditable
- a preset cannot exceed its persisted command-capacity limit through normal application writes
- each command-capacity upgrade is level-gated, ledger-backed, auditable, and applied at most once
- personal equipment and expendable weekly campaign assets have separate storage and lifecycle
- campaign contributions are immutable after the lock boundary
- reward issuance is idempotent and traceable to its source
- historical battles retain the versions needed for replay and audit
- snapshot reuse is limited so one player cannot become an unlimited farming target

## API Baseline

The initial API namespace is `/api/v1`. Planned resource groups include:

- player profile and resources
- alliance and content catalogs
- army presets and activation
- operation offers and battle start
- battle result and event stream
- current campaign and contributions
- research tree and unlocks
- replay metadata
- protected admin operations

Final endpoint shapes should be captured in an OpenAPI document alongside implementation. Public DTOs should be versioned independently from persistence entities.

## Scheduling

Daily and weekly work is implemented as explicit, persisted state transitions rather than assumptions based only on wall-clock time. The current campaign jobs open Monday matchups, resolve at Sunday 15:00 Belgrade time, recover an overdue unresolved week after restart, and retry notification delivery. Operation generation, richer strength updates, video rendering, and retention cleanup remain planned.

Campaign rows, matchup rows, and player reward rows have stable uniqueness boundaries, so retries cannot resolve a week or grant a reward twice. Timestamps are stored in UTC while the schedule is calculated in the configured IANA game timezone, preserving 15:00 through daylight-saving changes.

## Deployment Baseline

The planned Docker Compose topology contains:

- `frontline-backend`: Spring Boot application
- `frontline-db`: PostgreSQL 16+
- `frontline-miniapp`: static Mini App build served by Nginx or the backend
- `frontline-renderer`: Node/Chromium/FFmpeg worker, optional for the earliest MVP
- `frontline-proxy`: reverse proxy and TLS termination where required

Local, staging, and production environments should share image definitions while using separate secrets, databases, Telegram bots, and public URLs.

## Observability

The backend should provide structured logs, health/readiness endpoints, metrics, and durable audit records. Initial metrics should cover request latency and errors, active players, battle outcomes, scheduler duration/failures, campaign state, queue/render failures, economy issuance and sinks, and suspicious request rates.

## Architecture Evolution

Extract a module into a separate service only when deployment isolation, scaling behavior, data ownership, or failure containment provides a measured benefit. The video renderer is the earliest natural extraction because it is resource-heavy and consumes already-finalized replay data.
