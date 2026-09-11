# ADR 0004 Localized Identity and Alliance Catalog

## Status

Accepted

## Context

The initial bot exposed eight hardcoded alliances and Russian-only messages. The source specification requires a broad, neutral country and territory catalog, localized names, and explicit handling of Kosovo and Palestine. A command-only interface also needs a practical way to navigate hundreds of options without a Mini App.

## Decision

- Support `en`, `ru`, `es`, `pt`, `ar`, `id`, `hi`, and `tr` as persisted game locales.
- Infer a new account's initial locale from Telegram's optional `language_code`, with English fallback. Never overwrite a stored player choice with later locale hints.
- Store a versioned catalog of all 249 ISO 3166-1 alpha-2 entries plus `XK`. Render flags from codes and names through Java CLDR, with explicit neutral overrides for Kosovo and Palestine.
- Recommend a short language-relevant list during onboarding. Provide `/country` pagination and `/country <query>` accent-insensitive search across codes and names in every supported locale.
- Append newly selected alliances to the current open campaign without changing an existing matchup. Pair an odd unmatched alliance with an unused NPC side.
- Keep alliance changes unavailable until seasonal cooldown and rating-loss rules are implemented.
- Store nicknames only after confirmation. Apply NFC normalization, whitespace collapse, bidi/control removal, a 30-code-point cap, and resource-backed profanity masking before both pending storage and confirmation.

## Consequences

The Telegram interface remains compact while making the entire catalog selectable. Locale and nickname choices survive restarts and deployments. Java's locale data supplies most country translations, so the application must retain explicit overrides for names requiring stable product wording. Additional game locales require translations, Telegram command metadata, recommendation sets, and tests in the same change.
