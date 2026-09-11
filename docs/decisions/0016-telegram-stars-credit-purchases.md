# ADR 0016: Audited Telegram Stars Credit purchases

## Status

Accepted.

## Context

Players need an optional way to replenish the same Credits earned through normal play. Telegram digital goods must use Stars, payment webhooks can be retried, and refund requests need an operational path that does not expose an admin endpoint publicly. Credits may already have been spent when a refund is approved.

## Decision

- Offer the New Universe five-tier value ladder: 100/500/2,500/5,000/10,000 Credits for 20/85/350/600/1,000 Stars. It improves value by about 20% between tiers and reaches +100% Credits per Star versus the first tier.
- Use native Telegram `XTR` invoices from the command bot. The server owns all amounts and binds each payload to a package and Telegram player.
- Reject pre-checkout unless package, currency, amount, payload player, buyer, and persisted account agree.
- Make Telegram charge ID unique. Insert the payment, grant Credits, and append the wallet entry in one transaction so webhook retries cannot duplicate value.
- Keep `/paysupport` and admin `/refund`, `/reject`, and `/ask` in the bot. Only the configured private admin chat whose author has the same Telegram ID may execute admin commands.
- Call Telegram's Stars refund API before closing the support request, then reverse the full original Credit grant and append a refund ledger entry. Permit a negative Credit balance after refund so spent purchased value becomes debt rather than a refund exploit.
- Keep the admin ID and all Telegram credentials in production environment configuration, never in Git.

## Consequences

The initial integration remains command-only and adds no Mini App payment state. Purchases are reproducible from fixed server data and auditable separately from wallet changes. A user cannot gain Credits from a forged or repeated callback. Refund support is deliberately manual and limited to one private operator identity; multi-admin group support would require a separate actor allowlist and durable support-notification outbox.
