# ADR 0005: Personal Equipment and Tactical Composition

## Status

Accepted — 2026-09-11.

## Context

The first command-only battles used commander level plus abstract tactic and terrain bonuses. That proved deterministic resolution but gave the player no army-building progression and made tactics mostly labels. The source specification requires persistent personal equipment, CP-limited presets, equipment progression, and tactics whose usefulness depends on composition. It also requires personal units to remain separate from expendable weekly `CampaignAsset` production.

## Decision

- Keep a versioned, validated JSON catalog for seven fictional equipment classes: main battle tank, light armor, artillery, attack aircraft, fighter, mobile air defense, and reconnaissance vehicle.
- Give every player an idempotent starter grant containing three presets and a 10 CP active group with two tanks, one artillery unit, and one reconnaissance vehicle.
- Store each owned unit separately with level, durability, and acquisition origin. Buying consumes Credits; crafting consumes fewer Credits plus Materials; upgrading consumes both and adds 12% of base statistics per level through level 5.
- Let players reuse owned units across three presets. Only the active preset enters a battle. Its CP limit grows from 10 to 14 across commander levels.
- Compute battle power from five unit statistics. Score each tactic against a different weighted profile and explicit role requirements, then apply enemy and terrain counters separately.
- Bind battle callbacks to the active preset version and persist the complete immutable group snapshot with engine version 3.
- Keep generated equipment art generic and fictional. Serve it from the backend and use it in Telegram shop cards; emoji remain the compact list representation.
- Do not treat personal equipment as weekly consumables. Typed campaign assets will use separate tables and production rules.

## Consequences

Players can now build materially different armor, mobility, reconnaissance, fire-support, and air-defense compositions using bot commands alone. Upgrades and acquisitions are transactional and audited through both the resource ledger and equipment history. Historical battles remain reproducible after the player's inventory changes.

The initial leveling rule is intentionally simple and broad. Modules, branching research, repair state, richer per-unit events, and typed weekly production remain later changes and must preserve the snapshot and lifecycle boundaries established here.
