# ADR 0003 Scheduled Weekly Campaign

## Status

Accepted

## Context

Personal operations already generated resources and allowed Credits to be contributed, but those contributions had no terminal weekly outcome. The source specification requires a collective Sunday battle, protection for smaller alliances, retry-safe jobs, durable results, and rewards that cannot be granted twice.

The current client remains Telegram commands and inline buttons. Typed campaign assets and visual replay are not yet implemented, so this increment must close the weekly loop without pretending those systems exist.

## Decision

- Each ISO week has one persisted campaign row and four 1v1 alliance matchups.
- Matchups open on Monday and are paired by prior-week contribution power with a deterministic weekly tie-break.
- Contributions lock and battles resolve on Sunday at 15:00 in `Europe/Belgrade`.
- A recovery schedule checks for overdue open campaigns, including after application restart.
- The aggregate engine uses integer arithmetic, a secret-derived seed, a configurable contribution soft cap, and bounded NPC compensation.
- Every matchup persists its input totals, scores, seed hash, engine version, and four ordered phase events.
- Only contributing players receive the weekly resource reward. A unique week/player constraint makes issuance idempotent.
- Result delivery uses a PostgreSQL notification outbox. Telegram failures do not roll back battles or rewards and can be retried.

## Consequences

The MVP now has a complete daily-to-weekly retention loop entirely in Telegram. Credits still stand in for typed campaign assets, so class deficits, production, formation composition, and salvage remain future work. The result log is sufficient for command output and future replay consumers, but no Mini App or video renderer is introduced.
