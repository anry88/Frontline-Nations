# ADR 0007: Command Capacity and Force Tiers

## Status

Accepted

## Context

The first equipment implementation exposed Research Points but offered no spending path, and its commander-derived 10–14 CP limit left little room for long-term force growth. Product direction now requires meaningful armies and match categories reaching 1,000 CP while preserving short, deterministic command-only battles on compact maps.

Simulating every owned vehicle as an independent spatial actor would make high-tier battles slow, noisy, and hard to present in Telegram. Making level alone grant the full capacity would also leave Research Points without an economy role.

## Decision

- Persist each player's command capacity, starting at 10 CP.
- Commander level gates six force tiers; Research Points purchase each tier ceiling permanently and transactionally.
- The configured tiers are 10–25, 26–50, 51–100, 101–250, 251–500, and 501–1,000 deployed CP.
- The current battle tier is determined by actual deployed CP, not the player's maximum capacity. Groups below 10 CP cannot start an operation.
- Opponents are generated inside the player's deployed tier, adjusted by operation difficulty without crossing the tier boundary.
- Tier reward multipliers offset the higher acquisition cost of large armies.
- Identical owned units in the active preset are aggregated by equipment code and upgrade level into one battle formation. Quantity scales formation hit points and outgoing damage; equipment-specific movement, sight, range, fire mode, terrain rules, target selection, and capture eligibility remain unchanged.
- Personal combat groups stop at 1,000 CP. Larger conflicts belong to the future alliance-scale campaign layer and require separate contribution, command, map, and performance rules.

## Consequences

Research Points now have an explicit progression sink and commander XP remains relevant as the gate to larger echelons. Players can expand from a starter detachment to a corps without forcing hundreds of spatial actors onto a 7×7 map. Stored formation quantities and both deployed CP totals remain part of the deterministic battle snapshot and audit trail.

The aggregation deliberately gives one route and target order to all identical units in a formation. Future mass-battle work may split formations into multiple task groups, but it must use a new engine version and retain historical engine-v4 behavior.
