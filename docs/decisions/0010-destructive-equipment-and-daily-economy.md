# ADR 0010: Destructive equipment and daily economy

Status: Accepted — 2026-09-11; economy values partially superseded by ADR 0012

## Context

Combat Orders limited play without making equipment ownership meaningful. Research Points also created a second progression path whose spending surface was easy to miss. Weekly contributions copied a non-destructive group snapshot, allowing the same personal machines to remain available elsewhere while they were supposedly deployed.

## Decision

- Personal operations are unlimited. A monotonically increasing, single-use offer version replaces Combat Orders as callback replay protection.
- A destroyed owned unit is soft-deleted from usable inventory with its battle type and reference ID retained for audit. A routed formation keeps every unit that still has HP.
- `/contribute` reserves the concrete owned-unit IDs in the active group until the current Sunday battle resolves. Reserved equipment cannot enter a personal battle or be upgraded. The player may refresh the contribution before lock, which atomically replaces the reservation.
- Weekly formation losses are converted to whole-unit casualties by remaining formation power. A seed-derived stable ordering distributes those losses across NPC units and reserved player units. Surviving player units are released back to the hangar.
- Research Points and `/development` are retired. Capacity is calculated as `min(1000, commander level + 9)` and changes in the same transaction as XP.
- `/daily` originally granted 9,000–18,000 Credits. ADR 0012 redenominates those values to 90–180 without changing the intended purchasing power.
- Existing open contributions created before concrete unit reservation are voided by migration and must be submitted again.

## Consequences

The economy now has an explicit replacement source and a real equipment sink. Players can fight as often as their inventory permits, while the 100-day claim loop creates a retention reward without gating battles. Battle resolution, equipment losses, rewards, capacity changes, and audit records stay transactional. Weekly contributors must intentionally commit units and cannot reuse them before resolution.

The database retains historical Research Point balances, rewards, and capacity-upgrade rows for audit compatibility, but current gameplay neither grants nor spends that currency.
