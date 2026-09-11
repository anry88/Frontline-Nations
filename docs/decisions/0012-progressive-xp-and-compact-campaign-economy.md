# ADR 0012: Progressive XP and compact campaign economy

Status: Accepted — 2026-09-11

## Context

A flat 1,000 XP per commander level made every capacity increase equally fast and removed the feeling of long-term progression. Credit values had also grown into unnecessary thousands while ordinary battle rewards were too small relative to permanent equipment losses. Weekly rewards did not distinguish personal battlefield contribution and winning a country-level battle had no persistent effect on the following week.

## Decision

- Moving from commander level `L` to `L+1` costs `1,000 × L` XP. Total XP is retained and maps to triangular thresholds: levels 1–5 start at 0, 1,000, 3,000, 6,000, and 10,000 XP.
- Command capacity remains `min(1,000, level + 9)` CP. `/profile` displays XP earned inside the current level and the increasing next-level requirement.
- Credits and every Credit-denominated equipment price are redenominated at 1:100. Existing live balances round upward once and receive an auditable `CURRENCY_REBASE` ledger adjustment. Historical battle and transaction rows retain their original values and engine versions.
- `/daily` grants 90 Credits on day one and rises to 180 on day 100. Materials are not redenominated because they are a separate crafting resource.
- A standard first-tier personal victory pays a 20-Credit outcome reward; participation after defeat pays 2. Destroying enemy equipment pays one additional Credit and 10 XP for each destroyed enemy CP. Difficulty and deployed-force-tier multipliers apply to both components. The five-fight economy test requires average equal-force battle income plus one fifth of the base daily reward to cover average replacement losses.
- A contributing weekly winner receives 90 Credits and 600 XP before individual performance bonuses; a loser receives 30 Credits and 250 XP. This makes the winning base payout approximately three ordinary equal-force victories.
- Weekly player formations retain their contributor ID. A player receives 1 Credit and 50 XP per 100 enemy power on formations they finish, plus 5 Credits and 100 XP for every objective capture in which one of their surviving ground formations participates. Performance and every reward component are persisted for audit.
- The winning country receives a persisted seven-day ×1.2 multiplier for Credits and XP earned from personal battles and `/daily`. A new weekly result replaces the prior bonus for both countries in that pairing.
- Personal and weekly map URLs include `?v=<mapVersion>` so current battles display upgraded images instead of Telegram's cached pre-upgrade file.

## Consequences

Levels slow down predictably while each still grants the promised 1 CP. Small currency values remain readable, daily income continues to seed several battles, and combat replenishes part of what it destroys. Weekly outcomes now reward both collective victory and attributable player action, while the seven-day country bonus gives the result a visible effect on the next play cycle. Personal engine v7 and weekly engine v4 identify the changed reward and attribution contracts.
