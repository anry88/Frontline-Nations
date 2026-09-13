# Frontline Nations Architecture

This document describes the command-only MVP architecture and separates it from the broader target defined by the product specification.

## Architecture Goal

The first version should be a modular monolith that proves the core product loop before the team invests in microservices, a global geopolitical map, complex diplomacy, or large catalogs of licensed equipment.

The deployed MVP has two runtime surfaces:

- Telegram Bot commands, callback queries, and inline alliance-selection buttons
- Spring Boot backend as the authority for gameplay and economy

PostgreSQL stores players and their explicit locale/nickname settings, Telegram reachability, level-derived command capacity, daily reward state, processed Telegram updates, owned/reserved/destroyed personal units, three combat-group presets, equipment audit records, personal battles and their immutable group/map/opponent/tier snapshots, ordered spatial event JSON, campaign weeks and matchups, cumulative country ratings, contributions, weekly rewards, staged notification outbox entries, Telegram Stars payments/support requests, and wallet ledger entries. Scheduled jobs drive the weekly campaign; daily rewards are claimed explicitly through the bot. The backend renders saved events as MP4 replays on demand and for country-wide weekly result delivery; Mini App playback, equipment modules, and branching technologies remain future milestones.

### Identity, locale, and alliance selection

1. A new account maps Telegram's optional IETF `language_code` to one of eight supported locales, falling back to English for a missing or unsupported tag. Explicit `/language` choices and existing stored preferences are retained.
2. `/language` changes the persisted preference; later Telegram updates refresh only the locale hint and never overwrite that explicit choice.
3. `/nickname` normalizes whitespace, removes bidi/control characters, masks configured profanity, limits the result to 30 Unicode code points, and stores it as pending until the same player confirms the callback.
4. The versioned catalog contains all 249 ISO 3166-1 alpha-2 countries and territories plus explicit `XK`. Java CLDR provides localized display names, with explicit neutral names for Kosovo and Palestine.
5. `/country` offers language-relevant suggestions, locale-aware pages of ten, and accent-insensitive search across every supported translation and the two-letter code. Alliance selection remains immutable until seasonal switching rules are implemented.

## Core Data Flow

### Personal operation

1. The server deterministically generates five operation offers from a catalog of 24 battlefields for a single-use offer version.
2. The bot shows a named battlefield, biome, risk/reward tier, and tier-dependent intelligence for each offer.
3. The player maintains one of three reusable presets through `/army`. Command capacity is `level + 9 CP`, capped at the supported 1,000 CP maximum, making heavy armor, artillery, aircraft, air defense, and reconnaissance compete for space at every echelon.
4. The selected operation resolves to a versioned 9×12 odd-row offset sector map. Map version 5 gives every battlefield an authored deployment orientation and objective arrangement instead of reusing one north/south template; a deterministic graph connects entries and objectives with primary and alternate roads. The bot sends its immutable 1,536×1,536 PNG assembled from generated full-bleed hex terrain and objective blocks, and its coordinates match the server map snapshot.
5. The player binds the active group to an entry and a first objective, then chooses a behavior doctrine. Doctrines change route preference, holding behavior, movement order, and target selection; engine v10 applies no hidden tactic, counter, or terrain power percentage. Ambush formations may pause in cover near an unseen enemy, but periodically resume their advance so two opposing ambushes cannot deadlock outside weapon range. Defenders hold controlled objectives without an available target only while their side leads in objective control; tied or trailing defenders advance toward unsecured ground.
6. The backend binds callbacks to the current offer version and group version, rejects stale or replayed changes, derives a protected seed, and moves units through logical steps. Movement costs, line of sight, spotting, weapon range, minimum artillery range, cover, damage, and objective control are resolved using integer arithmetic. Equipment moves at most four road hexes per personal-battle step; a traversable cell whose terrain cost exceeds the remaining allowance still permits one cell of progress.
7. Ground units capture an uncontested objective after its configured number of consecutive steps. Existing control remains until an opponent completes the same process, so defenders may contest or retake it. Aircraft do not capture objectives.
8. The battle ends when one side holds all important objectives, one army has no combat-capable units, or the 48-step safety boundary routes the weaker remaining army by objective control and hit points. Reports describe that last case explicitly as a turn-limit decision and distinguish surviving equipment from destroyed equipment.
9. One transaction invalidates the offer, removes destroyed owned units, returns survivors to their presets, applies XP/Credits/Materials, recalculates level and capacity, and records the result, reward components, losses, force tier, deployed CP totals, snapshots, and typed spatial events. A standard first-tier win pays 20 Credits plus one Credit and 10 XP per destroyed enemy CP before difficulty/tier and active country bonuses.
10. The result includes a replay callback. On demand, the replay adapter reads the stored map/group/event snapshots, interpolates movement, draws fire, damage, losses, and objective control, encodes H.264 MP4 with FFmpeg, and sends the signed media URL through Telegram. Rendering never reruns combat.

### Personal equipment

1. A one-time idempotent grant creates three presets and the 10 CP starter force from the source specification.
2. `/shop` reads the versioned JSON catalog, orders unlocked classes before locked classes, and offers bulk purchase for Credits while keeping the player in the arsenal flow. Materials are spent only on equipment upgrades. Commander level gates later classes. Each class also declares movement profile/points, sight, finite minimum/maximum range, and fire mode.
3. `/upgrade` spends both resources and scales all base stats by a deterministic integer 12% per level through level 5.
4. Player balances, wallet ledger rows, owned-unit state, and equipment audit rows change in one transaction.
5. Generated fictional class icons are served by the backend and sent as Telegram equipment cards. A unit destroyed in either battle mode is soft-deleted from usable inventory while its audit history remains intact.
6. Research Points and `/development` are retired. Each commander level automatically adds 1 CP: level 1 is 10 CP, level 2 is 11 CP, continuing up to 1,000 CP. The cumulative XP threshold is triangular: moving from level `L` to `L+1` costs `1,000 × L` XP. `/shop` supports batches of 1, 5, or 25 units, `/upgrade` groups identical equipment and upgrades 1, 5, or 25 at once, and `/army` adds or removes one available unit of a selected class per callback.
7. Battle category follows deployed CP rather than account level. Rewards scale by the category's configured multiplier, and the generated opponent remains inside the same category.
8. To keep map complexity bounded, identical units are snapshotted into formations keyed by class and upgrade level. Formation quantity scales hit points and outgoing damage; movement, range, terrain access, spotting, targeting, and capture eligibility still follow the equipment definition.

### Telegram Stars monetization and support

1. `/stars`, `/buycredits`, and the arsenal payment button expose only five server-owned packs: 100/500/2,500/5,000/10,000 Credits for 20/85/350/600/1,000 Stars.
2. The bot sends a native `XTR` invoice with an empty provider token. Its payload binds the pack to the Telegram player ID.
3. A `pre_checkout_query` succeeds only when the player exists and buyer ID, payload player, currency, package, and Stars amount all match current server data.
4. `successful_payment` inserts the unique Telegram charge, updates Credits, and writes the `STARS_PURCHASE` wallet entry in the same transaction as Telegram update claiming. Retried charges do not credit twice.
5. `/paysupport` lists only unrefunded purchases, prevents multiple open requests for one payment, and forwards the selected purchase and reason to the configured private admin chat.
6. The same private admin identity may use `/refund`, `/reject`, or `/ask`. Telegram refund success is followed by an audited `STARS_REFUND` Credit reversal and request closure; Credits may become negative so already-spent value cannot survive a refund.
7. Bot tokens and the admin chat ID remain deployment environment values. They are never stored in the repository.

### Weekly campaign

1. The first observed campaign week and every week following a completed round include all 250 catalog countries and territories. Countries are ordered by cumulative rating descending and English name ascending, then paired adjacently. Therefore the initial equal-rating round is strictly English alphabetical: 1–2, 3–4, and so on through 125 matches. The Monday 00:05 UTC job remains an idempotent recovery trigger, but a completed Sunday round creates the next matchups immediately.
2. Every country receives a seed-derived random NPC equipment group that totals 10–25 CP. One CP is stored as 100 internal combat-power points; `/front` converts remaining power back to CP and reports the exact NPC machine count. Players use `/contribute` to inspect and independently commit, replace, or withdraw presets 1–3 before lock. Each contribution snapshots concrete owned-unit IDs, a side-valid edge entry, and a behavior doctrine. Reserved IDs cannot be reused by another preset or personal battle.
3. Contributions lock at Sunday 15:00 UTC.
4. The aggregate engine combines the NPC composition and player equipment snapshots, preserving equipment class, upgrade level, contributing player, source contribution, selected map entry, first objective, and tactic when it deploys armor, artillery, reconnaissance, air, and support formations. The contribution ID prevents two presets with identical unit classes from being merged and losing their distinct orders. Legacy active contributions with no stored first objective remain valid and use the prior deterministic automatic-target selection.
5. One of ten individually composed 15×21 versioned offset-grid weekly maps supplies its own front orientation, several coherent terrain regions, three distinct edge entries per side, a unique five-objective arrangement, and a connected road network with alternate routes. `/front` sends the pre-rendered upright rectangular map image. Movement, finite range, direct-fire line of sight, indirect artillery, cover, capture, loss, and recapture resolve deterministically for at most 120 turns. Weekly engines persist stable formation IDs, starting positions, movement, hits, destruction, and objective events so `/front` can offer an accurate aggregate replay after resolution. Existing v7 matchups retain their original 96-turn boundary and rules. Matchups created under v8 use the revised boundary and timeout decision, and allow opposing formations that converge on one objective hex to fight at zero distance instead of entering an eventless stalemate.
6. Current objective ownership scores by capture time; losing an objective removes its prior score. Destroyed enemy power and a configured fraction of allied surviving power complete the battle score. All-objective control and army destruction end early. Engine v8 decides a timeout by total score, then objective score and remaining power, which prevents a lower-scoring side from winning merely because it retained more equipment.
7. Every country starts from a configurable 10,000 rating. Rating v2 adds the winner's battle score. The loser gains no rating and loses a configurable percentage of accumulated rating. That percentage grows linearly from the configured minimum to maximum with the final total-score deficit; because total score includes retained objectives, enemy power destroyed, and surviving allied power, an efficient close defeat is penalized less than a rout. A nonzero rating is never erased completely. The rating version and loss bounds are snapshotted per matchup. Migration V18 rebuilds completed ratings chronologically from the common baseline and persisted results. The next week reorders all countries from the corrected rating.
8. One transaction stores map/input/result snapshots, score components, rating transitions and typed events, deterministically distributes casualties within each contributor-owned formation, returns survivors, removes losses, and issues one ledger-backed reward per contributor. A winning contributor starts at 90 Credits and 600 XP; final blows and capture participation add individually audited bonuses. The winning country receives a seven-day ×1.2 multiplier for Credits, XP, and Materials earned from personal battles; `/daily` applies it to Credits. A durable notification outbox is delivered after commit and retried independently.
9. Every reachable player whose selected country participated receives the localized result and that exact matchup's replay, regardless of whether the player contributed equipment. Text and replay delivery are tracked independently. A single dedicated campaign worker resolves due battles, prepares each `matchupId` replay once per delivery pass, and delivers one player's stages at a time without occupying webhook request threads. CPU-heavy simulation runs outside database transactions; each matchup is then committed in its own short transaction so a full 250-country round cannot hold campaign locks or the JDBC pool until every battle finishes. Committed matchups are skipped after a worker restart. The file cache keeps weekly artifacts for at least 168 hours in a persistent Docker volume, while personal artifacts retain the shorter configurable lifetime.
10. Permanent Telegram errors such as a blocked bot, deactivated user, or missing chat mark the player unreachable and abandon pending broadcasts. Any later authenticated inbound Telegram update clears that reachability marker for future broadcasts; transient Telegram and rendering failures remain retryable.
11. After a week resolves, `/front` presents the next open matchup and map; a localized `Previous battle` callback opens the latest completed result and its replay separately. `/contribute` immediately targets the next week. Campaign status, controls, results, event descriptions, map objectives, formation types, equipment names, ratings, and help copy use the player's selected locale across all eight supported languages.

### Player information surfaces

- `/rankings` reads the cumulative `alliance_ratings` table in stable rating/name order with ten countries per page. The player view returns the global XP top 10 and the requesting commander's exact place using one deterministic XP/Telegram-ID ordering.
- `/guide` is a five-section localized callback flow covering onboarding, personal combat, equipment and CP, weekly contributions, scoring, and rewards. Operational implementation details such as map dimensions and automatic NPC force creation are kept out of normal player status messages.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `telegram-adapter` | Telegram webhook, commands, callback queries, Mini App authentication |
| `player` | Account, profile, alliance membership, commander level, rating |
| `catalog` | Alliances, locations, units, modules, doctrines, balance configuration |
| `inventory` | Owned units, equipment, presets, repair and production state |
| `progression` | XP-derived command capacity, force-tier classification, and future prestige |
| `matchmaking` | Operation offers, opponent snapshots, difficulty bands |
| `battle-engine` | Versioned deterministic personal and aggregate simulations |
| `campaign` | Weekly matchups, campaign assets, deficits, contributions, rewards |
| `replay` | Snapshot/event projection, frame rendering, MP4 encoding, signed delivery, and bounded cache |
| `jobs` | Daily reset, snapshot refresh, campaign lifecycle, cleanup, rendering |
| `admin` | Protected catalog, balance, campaign, and operational controls |
| `monetization` | Fixed Stars packages, checkout validation, idempotent delivery, refunds, and payment support |

Modules may live in one deployable Spring Boot application while keeping dependencies explicit. Domain modules should not call Telegram or rendering code directly; they publish results that adapters deliver.

## Battle Engine Contract

Every completed battle should retain:

- battle type and engine version
- seed hash and, after completion where appropriate, verification material
- immutable player/opponent or formation inputs
- relevant balance and battlefield configuration version
- result summary
- ordered events with logical ticks and typed payloads

The current personal engine contract is version 10; new weekly matchups use spatial engine version 8. Versions 6/3 introduced odd-row offset coordinates so rectangular image adjacency, movement distance, pathfinding, range, and line of sight share one geometry. Personal v10 retains that geometry, the compact reward breakdown, bounded movement, and deadlock-free ambush and defense behavior; weekly v5 added replay-grade formation identity, starting positions, movement, and hit events, weekly v6 added source-contribution identity plus player-selected deployment and doctrine behavior, weekly v7 added player-selected first objectives with an automatic-target fallback for legacy contributions, and weekly v8 added zero-distance combat plus the 120-turn score-based timeout. After resolution the personal engine stores the battle seed and hash, commander-level snapshot, full player and generated opponent groups, map/version snapshot, group version, selected location/biome/difficulty/enemy archetype, deployment entry, first objective, both behavior doctrines, final objective control, typed movement/fire/capture events, end reason, rewards, and owned-unit casualty totals. Operation offers are bound to the player, game date, single-use offer version, and preset version; an old selection button cannot silently use a changed group.

The same engine version, seed, input snapshot, and configuration must reproduce the same outcome and event order. Replay clients may interpolate animations, but they may not invent gameplay outcomes.

## Persistence Baseline

The specification proposes PostgreSQL tables for players, alliances, battlefields, catalogs, owned units, modules, research, doctrines, combat groups, operations, battles, events, campaigns, contributions, results, and wallet transactions.

Implementation should refine this model through versioned migrations. Important invariants include:

- one Telegram identity maps to one player account unless an explicit account-linking flow is introduced
- resource balances change only through ledger-backed transactions
- Telegram payment delivery is unique by Telegram charge ID and every refund reverses its granted Credits
- one-time starter grants and equipment transactions are idempotent and auditable
- a preset cannot exceed its persisted command-capacity limit through normal application writes
- command capacity is derived from commander level and changes atomically with XP rewards
- a personal unit reserved for a weekly campaign cannot enter a personal battle or be upgraded until resolution
- destroyed personal units leave usable inventory while retaining auditable history
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

Daily and weekly work is implemented as explicit, persisted state transitions rather than assumptions based only on wall-clock time. `/daily` locks the player row and records the UTC calendar date, reward streak, total claims, and actual ledgered reward after any active country multiplier, so duplicate updates cannot grant twice. The campaign jobs idempotently ensure a contributable week, enqueue a dedicated single-thread worker to resolve Sunday 15:00 UTC battles, immediately prepare the next week's rating-seeded pairings after full resolution, activate the winner's persisted seven-day economy bonus, recover an overdue unresolved week after restart, render shared weekly replays, and retry staged notification delivery. Battle simulation does not hold a JDBC connection or database transaction; every completed matchup is persisted atomically and independently before the worker moves to the next one. A future open campaign is rescheduled to the configured game clock when the application observes it, so a timezone configuration change cannot leave the displayed lock later than the persisted lock. Webhook threads never execute that weekly pipeline. Replay rendering is synchronized per battle. Personal files expire after the configurable short retention window; weekly files use a distinct minimum 168-hour policy and persistent Compose storage. Presentation version v2 uses 3 FPS, half the previous playback speed, and a distinct cache/signature namespace so older fast artifacts are regenerated.

Campaign rows, matchup rows, and player reward rows have stable uniqueness boundaries, so retries cannot resolve a week or grant a reward twice. Timestamps and the game schedule use UTC, so daily boundaries and the 15:00 weekly lock do not move with daylight-saving changes.

## Deployment Baseline

The planned Docker Compose topology contains:

- `frontline-backend`: Spring Boot application
- `frontline-db`: PostgreSQL 16+
- `frontline-miniapp`: static Mini App build served by Nginx or the backend
- in-process `replay`: Java2D frame compositor plus FFmpeg in the backend image
- `frontline-renderer`: optional future extraction when render load justifies a separate worker
- `frontline-proxy`: reverse proxy and TLS termination where required

Local, staging, and production environments should share image definitions while using separate secrets, databases, Telegram bots, and public URLs.

## Observability

The backend provides health endpoints, Micrometer/Prometheus runtime metrics, durable wallet/equipment/payment audit records, and database-backed gauges for active players, concrete normalized referral campaigns, Stars charges, equipment transactions, and battle outcomes. Direct Telegram registrations remain separate; at most 24 referral series per period are exported and the remainder is aggregated as `other`. Event counters cover bot commands, callback actions, checkout stages, equipment actions, and personal battles. The importable Grafana board lives at `docs/grafana/dashboard.json`. Scheduler duration/failures, replay queue failures, and suspicious-request alerting remain the next observability additions.

## Architecture Evolution

Extract a module into a separate service only when deployment isolation, scaling behavior, data ownership, or failure containment provides a measured benefit. The video renderer is the earliest natural extraction because it is resource-heavy and consumes already-finalized replay data.
