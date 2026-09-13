# ADR 0014: Ordered front groups and player-facing information

## Status

Accepted

## Context

The weekly contribution surface exposed one numeric power total and silently replaced the player's only contribution. It did not preserve an entry or tactic, could not show or withdraw committed equipment, and hid the fact that all three reusable presets may be committed when their concrete units do not overlap. Ratings and rules also lacked dedicated player-facing surfaces, while operational details leaked into ordinary status copy.

## Decision

- A player may keep one active weekly contribution per preset, for at most three. Each row stores the immutable group snapshot, concrete owned-unit IDs, selected side-valid entry, and tactic.
- Replacing a preset atomically swaps exactly that contribution's reservation. Withdrawing before the Sunday lock voids the row and releases only its owned units. The same owned-unit ID cannot participate in two contributions even if it appears in multiple presets.
- Weekly formations retain the source contribution ID. Deployment uses its selected entry, and behavior doctrine changes route, holding, and target priorities without a hidden percentage power modifier.
- This deterministic rules change is recorded as weekly engine version 6; version 5 replay snapshots remain renderable.
- `/contribute` becomes the inspection and management surface. `/front` lists the player's submitted group compositions rather than an unexplained aggregate power number.
- `/rankings` provides stable ten-row country pages and the commander XP top 10 plus the requesting player's exact place.
- `/guide` provides five concise localized sections. Routine player messages omit implementation metadata such as grid dimensions and automatic NPC creation.

## Consequences

Reservation and casualty queries must join concrete unit IDs to their source contribution instead of joining every player contribution for the week. Historical rows remain readable with default maneuver orders and automatic entry fallback. Rating and guide callbacks are read-only and do not change authoritative battle state.

## Amendment: map markers and first objectives

- `/contribute` displays the same side-specific entry markers as the rendered weekly map: `A`–`C` for side A and `X`–`Z` for side B. Stable internal entry IDs remain unchanged in storage.
- Each new contribution stores a side-independent first objective selected from the five numbered map objectives. The formation prioritizes that objective while it is not held by its side, then resumes doctrine-based target selection.
- Existing active contributions retain their entry and doctrine. Their nullable first objective invokes the previous deterministic automatic-target behavior, so the schema change does not replace, withdraw, or reinterpret deployed units.
- This behavior is recorded as weekly engine version 7.
