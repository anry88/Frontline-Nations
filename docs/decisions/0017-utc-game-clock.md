# ADR 0017 UTC game clock

## Status

Accepted

## Context

The command MVP originally defined daily boundaries and the weekly campaign schedule in `Europe/Belgrade`. That regional reference is unfamiliar to much of the international player base, and daylight-saving changes make its UTC equivalent move during the year.

## Decision

- UTC is the default and production game timezone.
- Daily reward dates, Monday campaign opening, Sunday contribution lock, and weekly resolution use the UTC calendar.
- The weekly lock remains Sunday at 15:00, now fixed at 15:00 UTC throughout the year.
- Player-facing schedule text states UTC explicitly.
- When the application observes the current open campaign before its persisted deadline, it reconciles that deadline with the configured game timezone. Resolved and already-overdue campaign rows remain immutable.
- The Grafana dashboard also renders time in UTC.

This decision supersedes the `Europe/Belgrade` schedule clause in ADR 0003. It does not alter historical battle timestamps or resolved campaign rows.

## Consequences

- Players and operators have one stable, globally recognizable time reference.
- Daily boundaries and weekly execution no longer move with European daylight-saving changes.
- Deployments must set `GAME_TIMEZONE=UTC`; relying on an older production environment value would preserve the former behavior.
- A future open campaign can move to the new 15:00 UTC deadline when the updated application first ensures the current week.
