# Monetization module

Command-only Telegram Stars purchases and payment support.

- `StarsCreditCatalog` owns the fixed Credit/Stars ladder and invoice payload format.
- `StarsPaymentService` validates pre-checkout, delivers each Telegram charge once, records payment/support state, and reverses Credits after an approved Stars refund.
- `StarsMessages` keeps the player-facing flow aligned with all eight supported bot languages.
- `GameService` is the Telegram adapter for `/stars`, `/paysupport`, `/answer`, and the private-admin `/refund`, `/reject`, and `/ask` commands.

The database transaction containing a successful payment must insert `star_payments`, update `players.credits`, and append `wallet_transactions` together. Never trust amounts from callback data or accept an invoice whose payload player differs from the Telegram buyer. Production IDs and credentials belong only in environment configuration.
