# ADR 0008: Ranked spatial weekly campaigns

## Status

Superseded in part by ADR 0010 and ADR 0018

## Context

The phase-based weekly resolver did not represent terrain, equipment roles, capture timing, recapture, or a durable competition between every country in the catalog. A win/loss replacement rating would also let one costly defeat erase an otherwise leading season.

## Decision

Every weekly round includes all 250 neutral country/territory identifiers. Pairing order is cumulative rating descending and English display name ascending, with adjacent entries paired. This makes the first zero-rating round English-alphabetical and every later round performance-ranked.

Each country receives a deterministic random equipment group totaling 10–25 CP. A player may add or refresh one immutable snapshot of the active personal group before lock; this neither spends Credits nor consumes equipment. Engine v2 combines those real equipment compositions and upgrade levels into five aggregate formation classes per side on one of ten immutable 15×21 map snapshots, with a 96-turn boundary. Maps have three deployment entries per side and exactly five multi-turn capture points. Movement follows terrain; direct fire requires finite range and line of sight, artillery is indirect, and aviation has finite movement and attack range.

The score is the sum of currently retained capture-point awards, destroyed opponent power, and a configured percentage of surviving allied power. Capture awards decay by battle turn to a configured floor. A lost point contributes zero to its former owner, while a recapture earns the later, smaller award. All five points or total army destruction end the battle early. At the turn limit the side with more remaining power wins; objective and total scores break equal-force ties.

Rating is cumulative: each country adds its battle score whether it wins or loses. Match rows preserve before/after rating, component scores, map and formation snapshots, end reason, seed, and typed events. The V8 migration removes unresolved v1 pairings from open weeks so mixed rules cannot coexist, but never deletes resolved battles or rewards.

## Consequences

- A single loss cannot collapse a leader from the top to the bottom solely because old rating was replaced.
- Pairings are deterministic and cover the full catalog, including countries without active players; baseline/NPC power keeps those battles resolvable.
- Ten larger maps and aggregate formations add terrain tactics without simulating hundreds of individual vehicles.
- Every country can fight without players, while player-owned equipment directly changes its formation mix and power.
- Balance changes remain environment-configurable and historical results stay reproducible from persisted snapshots.
