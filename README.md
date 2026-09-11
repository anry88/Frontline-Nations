# Frontline Nations

Frontline Nations is a Telegram-first asynchronous military strategy game in which players complete short daily operations and contribute resources to weekly alliance fronts.

The repository contains a command-only MVP bot backed by Kotlin, Spring Boot, and PostgreSQL. The broader target product and architecture are described in [Documents/Frontline_TZ_v0.1_RU.docx](Documents/Frontline_TZ_v0.1_RU.docx). Mini App functionality is intentionally deferred.

![Frontline Nations emblem](assets/brand/frontline-nations-bot-avatar.png)

## Play

Open [@frontline_nations_bot](https://t.me/frontline_nations_bot) and use:

- `/start` — register and choose an alliance with inline buttons
- `/battle` — choose an operation, map entry, first objective, and behavior doctrine for the active group
- `/army` or `/hangar` — switch between three presets and select equipment within the CP limit
- `/shop` — inspect illustrated equipment cards and buy or craft a unit
- `/upgrade` — improve an owned unit from level 1 to 5
- `/daily` — claim 9,450+ Credits, grow a 100-day streak, and receive a random unlocked unit at the maximum streak
- `/profile` — inspect alliance, level progress, battle record, resources, capacity, and reward streak
- `/front` — inspect the current matchup, intelligence, countdown, or published result
- `/contribute` — commit the active equipment group until the Sunday battle; surviving units return and destroyed units are lost
- `/country` or `/country Serbia` — browse 250 countries and territories or search by localized name/code
- `/language` — choose English, Russian, Spanish, Brazilian Portuguese, Arabic, Indonesian, Hindi, or Turkish
- `/nickname Commander` — choose a sanitized nickname after explicit confirmation
- `/settings` — open language and profile settings
- `/help` — show command help

New accounts infer their initial language from Telegram and receive language-relevant country suggestions. The explicit language selection is retained even when Telegram later sends another interface locale. Existing accounts keep Russian until they choose another language.

## Product Principles

- A normal play session should produce a complete result in 1–3 minutes.
- Strategic depth comes from unit composition, terrain, routes, weapon ranges, behavior doctrines, modules, and campaign allocation rather than real-time micromanagement.
- Personal progression contributes to a weekly alliance war, while matchmaking, NPC garrisons, and contribution modifiers keep smaller alliances viable.
- Battles are calculated on the server. Replays visualize an immutable event log and never determine the result.
- Balance values, schedules, units, battlefields, rewards, and other content are data-driven.
- The system should scale without simulating every campaign unit as a real-time physical entity.

## Planned Experience

### Daily operations

Players may launch unlimited operations. The bot sends a pre-rendered square 9×12 hex-sector image with three entry points, illustrated strategic objects, connected roads, natural terrain transitions, and a visibly blocked outer rim. The player orders the active group through an entry toward its first objective and selects a movement/target-priority doctrine. The deterministic engine moves individual units, resolves spotting and finite-range fire, and tracks multi-step objective capture and recapture for at most 48 steps. A battle ends when one side controls every objective or the opposing army is destroyed or routed. Operations award commander XP, Credits, and Materials. Surviving equipment returns to its presets; units reduced to zero HP are removed from the usable inventory and recorded as battle losses.

`/daily` replaces the old battle-order refill. Day one grants 9,450 Credits, exactly three current starter-group replacement costs. Each consecutive claim adds 95 Credits, reaching 18,855 Credits on day 100. A missed Belgrade game day resets the streak. Day 100 and every consecutive day after it retain the maximum reward and add one deterministic random unit from the commander's unlocked catalog.

### Weekly campaigns

All 250 countries and territories enter 125 weekly pairings. With an empty table they are sorted by English name and paired adjacently; later rounds sort by cumulative battle rating, with English name as the stable tie-break. Contributions remain open until Sunday at 15:00 in the `Europe/Belgrade` timezone.

Each country receives a deterministic random NPC group whose actual equipment fits the first 10–25 CP category. Players reinforce their country with their own active equipment groups. Those machines are reserved until resolution and cannot simultaneously enter a personal battle; surviving units return and destroyed units are removed. Each matchup uses one of ten pre-rendered, versioned 15×21 hex maps with five illustrated capture points and five aggregate formation classes per side. Formations move across terrain and use finite weapon ranges; artillery can fire indirectly, while aircraft still cannot strike across the whole map. A captured point grants more score when secured early. Losing it removes its retained score, and a later recapture is worth less. Destroyed enemy power and surviving allied power also score. Capturing all five points or destroying the opposing army ends the battle early; at the 96-turn limit, remaining force decides the winner first. Rating accumulates each side's battle score instead of replacing it, preventing one defeat from erasing a leading country's season.

### Progression

The command MVP now includes seven configurable equipment classes, individual owned units, three reusable combat-group presets, commander-level unlocks, purchase and lower-credit crafting recipes, and five unit levels. Every class has map movement, sight, minimum/maximum weapon range, and a fire mode in addition to its five combat statistics. Aircraft movement remains finite; attack aircraft and fighters cannot strike across the whole map. Every level adds 12% to the unit's five base statistics.

New commanders receive the specification's 10 CP starter group: two main battle tanks, one artillery unit, and one reconnaissance vehicle. Capacity follows commander level directly: level 1 provides 10 CP, level 2 provides 11 CP, and every further level adds 1 CP up to the supported 1,000 CP ceiling. Research Points and manual capacity purchases are retired. Battles are classified by actually deployed power: 10–25, 26–50, 51–100, 101–250, 251–500, and 501–1,000 CP. Large groups are simulated as homogeneous formations by equipment class and level, preserving tactical differences without creating hundreds of independent map actors. Modules, branching technology choices, doctrine perks, repairs, and seasonal prestige remain planned.

## Target Architecture

```mermaid
flowchart LR
    A["Telegram Bot"] --> D["Spring Boot backend"]
    B["Telegram Mini App"] --> D
    C["Admin interface"] --> D
    D --> E["Domain modules"]
    E --> F["Deterministic battle engine"]
    E --> G["PostgreSQL"]
    D --> H["Schedulers and jobs"]
    F --> I["BattleEvent log"]
    I --> B
    I --> J["Video renderer"]
```

The MVP currently uses:

- Kotlin and Spring Boot backend
- PostgreSQL 16+ persistence
- Telegram Bot commands and inline flows
- Docker Compose for local and production-like environments

The diagram also shows the planned Mini App, admin interface, replay stream, and optional renderer; those components are not part of the command-only MVP.

See [DOCUMENTATION.md](DOCUMENTATION.md) for module boundaries and data flow.

## Planned Repository Layout

```text
src/           Spring Boot application, migration, and tests
compose.yml    Application and PostgreSQL containers
scripts/       Telegram and deployment helpers
assets/        Brand assets and generated source atlases
src/main/resources/static/assets/units/  Generated equipment-class icons
src/main/resources/static/assets/maps/   Generated terrain/object blocks and immutable square battle-map PNGs
docs/          Product, architecture, and repository guidance
Documents/     Source specifications and retained project materials
```

Mini App and replay-renderer directories will be added only when those milestones begin.

## Documentation

- [Product overview](docs/product-overview.md)
- [Architecture](DOCUMENTATION.md)
- [Architecture baseline decision](docs/decisions/0001-architecture-baseline.md)
- [Localized identity and alliance catalog decision](docs/decisions/0004-localized-identity-and-alliance-catalog.md)
- [Personal equipment and tactical composition decision](docs/decisions/0005-personal-equipment-and-tactical-composition.md)
- [Spatial personal battles decision](docs/decisions/0006-spatial-personal-battles.md)
- [Command capacity and force tiers decision](docs/decisions/0007-command-capacity-and-force-tiers.md)
- [Ranked spatial weekly campaigns decision](docs/decisions/0008-ranked-spatial-weekly-campaigns.md)
- [Graphical hex-map delivery decision](docs/decisions/0009-graphical-hex-map-delivery.md)
- [Destructive equipment and daily economy decision](docs/decisions/0010-destructive-equipment-and-daily-economy.md)
- [Contributor guide](CONTRIBUTING.md)
- [Agent guide](AGENTS.md)
- [GitHub About metadata](docs/github-about.md)
- [Source specification](Documents/Frontline_TZ_v0.1_RU.docx)

## Development Status

Current phase: command-only MVP with persistent equipment progression, spatial personal operations, and ranked spatial weekly battles.

The current implementation proves localized registration and settings, a versioned 250-entry country/territory catalog, moderated nicknames, a seven-class equipment catalog, transactional bulk purchase/crafting/upgrades, three CP-limited presets, level-derived command capacity, six battle categories up to 1,000 CP, unlimited spatial personal battles with permanent equipment casualties, a ledger-backed 100-day daily reward loop, five operation offers drawn from 24 battlefields with 24 mapped variants, 125 rating-seeded weekly pairings on ten larger maps, a random 10–25 CP NPC equipment group for every country, reserved and destructible player reinforcement, timed aggregate spatial resolution, capture/destruction/survival scoring, idempotent campaign rewards, and durable Telegram notifications. Graphical replays, seasonal alliance switching, modules, typed campaign assets, branching technologies, Mini App, and video rendering remain planned.

Run tests with `GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test`. For a local Docker run, copy `.env.example` to an ignored `.env`, replace every secret, create the PostgreSQL data directory, and run `docker compose up --build`.

Production is published at [frontline-nations.tg-games.com](https://frontline-nations.tg-games.com). See [the Home Data Center runbook](docs/production/home-data-center.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
