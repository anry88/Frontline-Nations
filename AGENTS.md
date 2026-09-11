# Frontline Nations Agent Guide

Repository-level guidance for coding agents and automated review tools.

## Current State

- This repository contains a minimal command-only Telegram bot implemented with Kotlin, Spring Boot, JDBC/Flyway, and PostgreSQL.
- [Documents/Frontline_TZ_v0.1_RU.docx](Documents/Frontline_TZ_v0.1_RU.docx) is the primary product and technical source.
- [README.md](README.md) is the public overview, [docs/product-overview.md](docs/product-overview.md) summarizes product intent, and [DOCUMENTATION.md](DOCUMENTATION.md) defines the target engineering boundaries.
- The implemented surface is localized `/start`, `/country`, `/language`, `/nickname`, `/settings`, `/army` and `/hangar` presets, bulk `/shop` purchase/crafting, `/upgrade`, `/daily`, an unlimited formation-based spatial entry/objective/doctrine `/battle` flow with permanent equipment casualties across six categories up to 1,000 CP, `/profile`, ranked spatial weekly views in `/front`, three independently ordered and withdrawable reserved-unit contributions through `/contribute`, paginated `/rankings`, paginated `/guide`, and `/help`. Both battle flows send pre-rendered square map images and expose event-log MP4 replays after resolution; weekly results and videos are also delivered asynchronously to every reachable player in the matched countries. Mini App, seasonal alliance switching, modules, and branching technologies remain planned.
- Do not describe planned behavior as implemented. Label plans, examples, and target architecture explicitly until code and tests support the claims.

## First Pass For Any Agent

1. Inspect the current branch, worktree, recent commits, and remotes before editing.
2. Read this file, [README.md](README.md), and [DOCUMENTATION.md](DOCUMENTATION.md).
3. Read the relevant sections of the source specification before changing gameplay or architecture.
4. Inspect the closest package README once implementation directories exist.
5. Preserve unrelated user changes and source documents.
6. Prefer the smallest coherent change and verify it with the relevant build or test command.

## Source Of Truth

Use this precedence when documents disagree:

1. Explicit requirements in the current task.
2. Accepted architecture decision records under `docs/decisions/`.
3. The source specification in `Documents/`.
4. Current executable behavior and tests for already implemented features.
5. Summaries in `README.md`, `DOCUMENTATION.md`, and `docs/`.

If executable behavior intentionally departs from the specification, record the decision and update the affected documentation in the same change.

## Repository Map

- `src/main/kotlin/com/tggames/frontline/`: Kotlin/Spring Boot modular monolith.
- `src/main/resources/db/migration/`: PostgreSQL migrations managed by Flyway.
- `src/test/`: deterministic-engine and application tests.
- `src/main/resources/db/migration/V2__battle_choices_and_progression.sql`: battle metadata, research currency, and commander statistics.
- `src/main/resources/db/migration/V3__weekly_campaign_battles.sql`: campaign weeks, matchups, results, rewards, and notification outbox.
- `src/main/resources/db/migration/V10__destructive_equipment_and_daily_rewards.sql`: daily reward streaks, offer nonces, level-derived capacity migration, equipment reservation, and destruction audit state.
- `src/main/resources/db/migration/V11__progressive_levels_and_campaign_economy.sql`: compact Credits rebase, progressive XP levels, contributor reward audit, and weekly country bonuses.
- `src/main/resources/db/migration/V12__front_squads_and_orders.sql`: per-preset weekly contributions, concrete reserved unit IDs, entries, and tactics.
- `src/main/resources/db/migration/V13__campaign_delivery_and_player_reachability.sql`: staged result/replay delivery and durable Telegram reachability.
- `src/main/kotlin/com/tggames/frontline/progression/CommanderProgression.kt`: authoritative cumulative XP curve and current-level progress.
- `src/main/resources/db/migration/V4__player_locale_and_nickname.sql`: player locale, Telegram locale hint, nickname, and pending confirmation.
- `src/main/kotlin/com/tggames/frontline/campaign/`: weekly schedule, pairing, aggregate battle, reward, and notification logic.
- `src/main/kotlin/com/tggames/frontline/catalog/`: data-driven personal equipment definitions and balance validation.
- `src/main/kotlin/com/tggames/frontline/inventory/`: starter grants, owned units, presets, purchases, crafting, and upgrades.
- `src/main/resources/catalog/equipment-catalog.json`: localized unit costs, roles, statistics, unlocks, and icon paths.
- `src/main/resources/static/assets/units/`: generated fictional equipment-class icons served by the backend.
- `src/main/resources/db/migration/V5__personal_equipment_and_battle_groups.sql`: inventory, presets, audit history, and battle snapshots.
- `src/main/kotlin/com/tggames/frontline/battle/`: versioned personal engines, rectangular odd-row offset maps with legacy axial compatibility, deterministic movement/fire/capture events, and legacy replay support.
- `src/main/kotlin/com/tggames/frontline/replay/`: event-log projection, Java2D frame composition, FFmpeg encoding, signed MP4 delivery, and expiring local cache.
- `src/main/resources/catalog/battle-maps.json`: version-5 9×12 terrain maps with unique authored entry/objective layouts.
- `src/main/resources/static/assets/maps/`: generated hex/object blocks plus 24 personal and 10 weekly square PNG maps.
- `scripts/generate-map-catalogs.py`: deterministic authored-anchor generator for distinct personal and weekly front orientations, objectives, terrain, and looped road layouts.
- `scripts/render-battle-map-assets.py`: deterministic full-bleed tile normalization, terrain-transition, connected-road, unique edge-marker, objective-placement, blocked-rim, and upright square-image pipeline.
- `src/main/resources/db/migration/V6__spatial_personal_battles.sql`: map/route/opponent snapshots, spatial events, final objective state, and end reason.
- `src/main/kotlin/com/tggames/frontline/progression/`: command-capacity expansion and force-tier rules.
- `src/main/resources/catalog/force-tiers.json`: deployed-CP category boundaries and reward multipliers through 1,000 CP.
- `src/main/resources/db/migration/V7__command_capacity_and_force_tiers.sql`: persisted capacity, upgrade audit, larger presets, and battle-tier metadata.
- `src/main/resources/db/migration/V8__ranked_spatial_weekly_battles.sql`: weekly map/result snapshots, cumulative alliance ratings, and v2 pairing reset.
- `src/main/resources/db/migration/V9__larger_graphical_battle_maps.sql`: larger open weekly snapshots and the 96-turn boundary.
- `src/main/resources/catalog/battlefield-map-index.json`: one spatial map assignment for every personal battlefield.
- `src/main/resources/catalog/weekly-battle-maps.json`: ten large version-5 weekly maps with unique five-objective layouts.
- `src/main/kotlin/com/tggames/frontline/i18n/`: supported locales and command-interface translations.
- `src/main/resources/catalog/alliance-codes.txt`: versioned 249-entry ISO catalog plus explicitly supported Kosovo.
- `compose.yml`: production-shaped application and PostgreSQL services.
- `assets/brand/`: repository emblem and Telegram avatar.
- `scripts/`: operational helpers, including Telegram webhook/profile configuration.
- `docs/production/`: Home Data Center runbook.
- The module paths below remain the target as the MVP grows:
- `backend/src/main/kotlin/.../telegram/`: Telegram webhook, commands, callbacks, and Mini App authentication.
- `backend/src/main/kotlin/.../player/`: account, profile, alliance, progression, and rating.
- `backend/src/main/kotlin/.../catalog/`: alliances, battlefields, units, modules, doctrines, and balance configuration.
- `backend/src/main/kotlin/.../inventory/`: owned units, equipped modules, repairs, and combat-group presets.
- `backend/src/main/kotlin/.../matchmaking/`: operation offers and opponent snapshots.
- `backend/src/main/kotlin/.../battle/`: deterministic personal and aggregate simulations.
- `backend/src/main/kotlin/.../campaign/`: weekly matching, contributions, deficits, resolution, and rewards.
- `backend/src/main/kotlin/.../replay/`: immutable battle events and replay delivery.
- `backend/src/main/kotlin/.../jobs/`: resets, snapshots, campaign transitions, rendering, and cleanup.
- `backend/src/main/kotlin/.../admin/`: protected content and operations endpoints.
- `miniapp/`: Telegram Mini App UI, map, hangar, research, statistics, and replay playback.
- `renderer/`: optional replay-to-video pipeline.
- `infra/`: Docker Compose, proxy, environment, and deployment assets.
- `docs/`: human- and agent-facing product and engineering documentation.

Update this map when package names or runtime components change.

## Non-Negotiable Domain Contracts

- The server is authoritative for battle outcomes, rewards, inventory, and campaign state.
- A battle result must be reproducible from the engine version, seed, inputs, and configuration snapshot.
- Replay and video layers consume `BattleEvent` output; they must not recalculate or alter results.
- Use integer or fixed-point arithmetic where cross-version determinism matters.
- Include a secret server value when deriving unrevealed battle seeds. Do not expose future random rolls.
- Wallet and inventory mutations must be transactional, auditable, and idempotent where retries are possible.
- Validate Telegram `initData` server-side. Never trust player identity, reward amounts, combat stats, or timestamps supplied by the client.
- Do not hardcode balance data that the specification identifies as configurable.
- Personal units committed with `/contribute` are reserved by concrete ID until weekly resolution; NPC campaign units remain separate, non-owned assets.
- Real country and territory names are neutral game identifiers. Avoid political claims or inferred sovereignty in copy, data, maps, and coordinates.

## Engineering Boundaries

- Begin with a modular monolith. Do not introduce distributed services before measured scaling or isolation needs justify them.
- Keep controllers/adapters thin; domain services own rules and transactions.
- Keep persistence models from leaking directly into public API contracts.
- Version battle-engine behavior and retain enough input/configuration data to reproduce historical results.
- Prefer additive API evolution. When changing an API contract, update the bot, Mini App, admin surface, tests, and documentation together.
- Store timestamps in UTC and make the game timezone explicit for daily and weekly schedules.
- Background jobs must tolerate retries, duplicate delivery, and process restarts.
- Use PostgreSQL migrations for schema changes; never rely on implicit schema creation in production.

## Testing Priorities

At minimum, add tests for:

- deterministic replay of a known battle seed and input snapshot
- combat formula boundaries, clamps, and fixed-point rounding
- authorization and Telegram authentication failure paths
- idempotent rewards, wallet transactions, contributions, and scheduled jobs
- campaign lock and resolve state transitions
- deterministic NPC group generation, equipment snapshot idempotency, and weekly rating rules
- persistence migrations and API serialization contracts

Every bug fix should include a regression test when practical. Do not weaken or delete a failing test merely to make a build green.

## Security And Privacy

- Never commit bot tokens, database credentials, server salts, session secrets, production URLs with embedded credentials, or personal data.
- Use environment variables or ignored local configuration with committed example files.
- Apply rate limits to battle starts, callbacks, replay access, and other abuse-prone endpoints.
- Log security-relevant events without logging Telegram auth payloads, secrets, or unnecessary personal data.
- Protect admin endpoints separately from player authentication and record administrative actions.

## Documentation Rules

- Update `README.md` when public scope, stack, status, or setup changes.
- Update `DOCUMENTATION.md` and the nearest package README when module boundaries or runtime data flow changes.
- Add or amend an ADR when a durable architecture decision changes.
- Keep [docs/github-about.md](docs/github-about.md) aligned with the public repository position.
- Preserve the original specification unless the task explicitly requests editing it; record implementation decisions in Markdown documentation instead.

## Git Workflow

- The initial repository branch is `main`. Introduce a separate development-branch policy only through an explicit project decision.
- Before committing, inspect `git diff`, `git status`, and the current branch.
- Keep commits focused and use imperative commit subjects.
- Do not rewrite shared history, force-push, or push to a different remote without explicit authorization.
- Do not commit generated build output, IDE state, runtime databases, logs, secrets, or rendered video artifacts.

## Completion Checklist

- Relevant tests and build checks pass.
- Determinism and idempotency constraints remain intact.
- API consumers are compatible with any contract changes.
- No secrets or generated artifacts are staged.
- Documentation reflects the implemented state rather than intended future behavior.
- `git diff --check` reports no whitespace errors.
