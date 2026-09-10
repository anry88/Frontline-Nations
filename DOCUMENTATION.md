# Frontline Nations Architecture

This document describes the command-only MVP architecture and separates it from the broader target defined by the product specification.

## Architecture Goal

The first version should be a modular monolith that proves the core product loop before the team invests in microservices, a global geopolitical map, complex diplomacy, or large catalogs of licensed equipment.

The deployed MVP has two runtime surfaces:

- Telegram Bot commands, callback queries, and inline alliance-selection buttons
- Spring Boot backend as the authority for gameplay and economy

PostgreSQL stores players, processed Telegram updates, battles, ordered round-event JSON, contributions, and wallet ledger entries. A scheduled job restores daily Combat Orders. Mini App, full replay visualization, combat-group composition, and weekly campaign resolution remain future milestones.

## Core Data Flow

### Personal operation

1. The server deterministically generates three operation offers for the player's current daily-order state.
2. The bot shows a named battlefield, biome, risk/reward tier, and tier-dependent intelligence for each offer.
3. The player selects an operation and one of five tactical orders with inline buttons.
4. The backend rejects stale callbacks, derives a protected seed, and simulates 8–12 logical rounds.
5. One transaction consumes the Combat Order and records the result, rewards, statistics, operation metadata, and ordered `BattleEvent` JSON.
6. The bot explains the tactic and terrain modifiers and presents three battle highlights. A full replay UI remains planned.

### Weekly campaign

1. A Monday job creates balanced alliance matchups and selects battlefields.
2. Players manufacture and contribute separate campaign assets during the week.
3. Periodic calculations publish coarse strength and deficit signals without exposing exact enemy composition.
4. Contributions lock before resolution.
5. The battle engine aggregates contributions into formations or cohorts and resolves the campaign.
6. The system persists results, contribution metrics, rewards, and replay events before notifications or rendering begin.

## Module Boundaries

| Module | Responsibility |
| --- | --- |
| `telegram-adapter` | Telegram webhook, commands, callback queries, Mini App authentication |
| `player` | Account, profile, alliance membership, commander level, rating |
| `catalog` | Alliances, locations, units, modules, doctrines, balance configuration |
| `inventory` | Owned units, equipment, presets, repair and production state |
| `progression` | XP, research nodes, unlocks, doctrine points, prestige |
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

The current engine contract is version 2. After resolution it stores the battle seed and hash, commander-level snapshot, selected location, biome, difficulty, enemy archetype, tactic, 8–12 round events, and rewards. Operation offers are bound to the player, game date, and current order count; an old inline button cannot consume a newer order.

The same engine version, seed, input snapshot, and configuration must reproduce the same outcome and event order. Replay clients may interpolate animations, but they may not invent gameplay outcomes.

## Persistence Baseline

The specification proposes PostgreSQL tables for players, alliances, battlefields, catalogs, owned units, modules, research, doctrines, combat groups, operations, battles, events, campaigns, contributions, results, and wallet transactions.

Implementation should refine this model through versioned migrations. Important invariants include:

- one Telegram identity maps to one player account unless an explicit account-linking flow is introduced
- resource balances change only through ledger-backed transactions
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

Daily and weekly work should be implemented as explicit, persisted state transitions rather than assumptions based only on wall-clock time. Jobs include daily order reset, operation generation, opponent snapshot refresh, campaign open, strength update, contribution lock, campaign resolution, reward delivery, video rendering, and retention cleanup.

All jobs need a stable idempotency key and must be safe after retries or restarts. Store timestamps in UTC; configure the product timezone explicitly.

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
