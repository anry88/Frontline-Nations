# ADR 0015: Asynchronous country-wide campaign delivery

## Status

Accepted.

## Context

Weekly results and replays belong to every player in the two matched countries, not only commanders who contributed equipment. Battle resolution, video encoding, and Telegram delivery are potentially slow and must not occupy webhook request threads. Telegram may accept the result text and fail the replay separately, while permanently unreachable accounts should not be retried every week.

## Decision

- Sunday resolution and recovery triggers enqueue one dedicated single-thread campaign worker.
- The worker resolves due campaigns, renders each saved matchup replay through the downstream replay module, and processes recipients sequentially.
- The durable outbox binds every recipient to the exact matchup and country, with separate timestamps for result text and replay media.
- All reachable players in the two participating countries receive an outbox row, regardless of equipment contribution. Rewards remain contributor-only.
- Retryable rendering, transport, or Telegram failures increment the outbox attempt counter without blocking later recipients in the same pass.
- Permanent Telegram rejections mark the player unreachable and abandon all pending broadcasts for that player. A later inbound update clears the player marker for future campaigns.
- Replay presentation v2 defaults to 3 FPS and uses a new cache/signature namespace so prior faster artifacts are not reused.
- A delivery pass memoizes one replay artifact per `matchupId`, avoiding repeated snapshot deserialization for recipients of the same battle. The shared weekly MP4 has a separate minimum 168-hour retention and lives in a persistent Compose volume; personal replay retention remains independently configurable.

## Consequences

Command handling remains independent of weekly CPU and network work, one recipient failure cannot stop the rest of the countries, and each weekly video is normally encoded only once even when the application is redeployed during its delivery week. The persistent cache consumes bounded storage for one week of matchup videos. Delivery is at-least-once around the narrow failure window between Telegram acceptance and persistence of the stage timestamp; Telegram does not provide an idempotency key for these sends. A single application instance remains the deployment assumption; multi-instance delivery would require explicit outbox row claiming.
