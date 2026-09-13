# ADR 0019: Continuous front rollover and localization

## Context

After a Sunday campaign resolved, the calendar still identified the completed ISO week until Monday. `/front` therefore kept returning the closed matchup and `/contribute` could not target the next battle. The completed replay still had to remain accessible. Weekly event summaries also rendered stored technical objective and formation identifiers, while several newer front and rating messages only supplied English and Russian copy.

## Decision

- The campaign service distinguishes the calendar week from the currently contributable week. If the calendar week is fully resolved, it idempotently creates the next ISO week's rating-seeded matchups immediately.
- Resolution performs that rollover in the same worker pass after the final matchup commits. The Monday opening schedule remains a recovery trigger.
- `/contribute`, withdrawal, deployment selection, and the planning map target the contributable week.
- `/front` shows only the next open matchup. A localized `Previous battle` callback opens the latest prior result and replay for the player's country as a separate message.
- Typed weekly events are localized at presentation time from stored objective IDs, equipment codes, and formation types. Stored events and replay inputs remain unchanged.
- Player-facing front, contribution, weekly-result, rating, and help text uses complete eight-language message entries. Telegram language detection accepts supported primary tags and falls back to English; an explicit `/language` selection remains persistent.

## Consequences

The weekly loop has no closed Sunday-to-Monday gap, while historical results remain inspectable. Pairings are still deterministic from the committed rating table and creation stays idempotent. Localization changes do not alter battle simulation, snapshots, casualties, scores, or old replay determinism.
