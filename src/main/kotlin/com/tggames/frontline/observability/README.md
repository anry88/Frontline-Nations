# Observability package

`GameMetrics` owns low-cardinality Micrometer instruments for the Telegram command UI and refreshes database-backed gauges once per minute. Event labels must stay bounded: never add Telegram IDs, nicknames, raw `/start` payloads, payment charge IDs, battle IDs, or callback data as tags.

Prometheus naming is derived from Micrometer names. For example, the `frontline.bot.command` counter is exported as `frontline_bot_command_total`. Persistent gauges are rebuilt from the database so dashboard totals survive application restarts; event counters represent activity observed by the current Prometheus history.
