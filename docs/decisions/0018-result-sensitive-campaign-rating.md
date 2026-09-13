# ADR 0018: Result-sensitive campaign rating

## Status

Accepted

## Context

Adding every side's weekly battle score let a defeated country rise in the ranking. Retaining a fixed fraction of prior rating would avoid that increase, but would treat a narrow, efficient defeat and a rout identically. Engine v7 could also select a time-limit winner by remaining force even when that side had a lower total battle score.

The first live weekly round was already resolving under engine v7 when these issues were identified. Its deterministic battle events and outcomes must not change mid-run, while its cumulative rating must use the corrected rule before the next pairing is created.

## Decision

All countries begin a season at a configurable common rating, initially 10,000. This gives the first-round loser rating that can actually be reduced and lets close and decisive first-round defeats produce different standings. Rating v2 adds the winner's actual battle score to its previous cumulative rating. The loser gains no points. Instead, it loses a percentage of its previous rating based on the final total-score deficit:

`severity = max(opponent score - own score, 0) / max(opponent score, own score, 1)`

`loss percentage = minimum + (maximum - minimum) × severity`

Integer basis points and ceiling division make the calculation deterministic. The default configurable range is 2–15%. A country at zero remains at zero; a country with accumulated rating retains at least one point. Total score already combines retained objectives, destroyed enemy power, and surviving allied power, so this one margin accounts for battlefield efficiency without separately rewarding the same component twice.

Each matchup snapshots the rating version and its minimum/maximum loss bounds. Migration V18 resets countries to the v2 baseline and replays completed matchups in chronological order from their immutable stored scores and winner, updating before/after audit fields and the aggregate rating table.

Weekly battle engine v8 extends newly created matchups from 96 to 120 turns and changes timeout winner selection. Capturing every objective and destroying the enemy army remain decisive end conditions. At the turn limit, total battle score decides first, followed by objective score and remaining power. Existing matchups are explicitly assigned engine v7 and preserve their snapshotted 96-turn boundary; only newly created matchups default to v8 and 120 turns.

## Consequences

- A loss cannot increase a country's rating.
- Close defeats preserve more history than decisive defeats, without allowing a single result to erase the full accumulated rating.
- The rating calculation remains configurable, deterministic, auditable, and reproducible after configuration changes.
- The in-progress first round preserves its v7 simulations while receiving one chronological v2 rating rebuild from a common baseline before the next week's pairings.
