# Frontline Nations Product Overview

Frontline Nations is a planned asynchronous multiplayer strategy game designed around Telegram. It combines quick personal battles with a longer collective campaign: every short session develops the player's commander and can strengthen an alliance in a weekly war.

## Core Loop

A player opens the bot, reviews the state of the current campaign, and chooses from several operations tied to named real-world landscapes. Before battle, the player selects a saved combat group and one tactic. The server resolves the encounter immediately and returns rewards and an optional replay.

The command-only MVP now implements the first strategic slice of this loop: three operation offers expose different intelligence and reward multipliers, then five inline tactical orders interact with enemy archetype and terrain. Combat-group selection and full replay playback remain planned.

Between battles, players spend credits, research points, and materials on modules, research, repairs, production, and doctrines. They may also manufacture expendable campaign assets and contribute them to their alliance's weekly theater.

## Strategic Depth

Combat groups are constrained by Command Points rather than a simple slot count. Heavy armor, artillery, reconnaissance, aircraft, and air defense compete for the same budget. Terrain, intelligence, tactics, module trade-offs, and active doctrine perks determine whether a composition is suitable for an operation.

Higher tiers should broaden specialization rather than provide unconditional percentage upgrades. Commander progression unlocks options gradually, while seasonal prestige emphasizes recognition and cosmetics instead of endless power growth.

## Weekly Alliance War

The campaign follows a weekly rhythm:

- Monday: match alliances of similar active strength and announce battlefield conditions.
- Monday–Saturday: run personal operations, produce assets, and contribute to the alliance.
- Saturday: publish an incomplete reconnaissance summary.
- Sunday: lock contributions, resolve the aggregate battle, publish replay/highlights, and award results.

The command-only MVP implements the scheduling and aggregate-result portion: dynamic weekly 1v1 matchups for selected alliances, Sunday 15:00 Belgrade resolution, capped contributions, limited NPC compensation, four battle phases, contributor rewards, and Telegram notifications. Campaign-asset classes, deficit-specific bonuses, and visual replay remain planned.

The design supports many country- and territory-named alliances without allowing population alone to decide every campaign. Matchmaking, NPC garrisons, underdog factors, contribution caps, and dynamic shortage bonuses are planned balancing tools.

Names, flags, and map locations are neutral fictional-game identifiers. They do not express a claim about recognition, sovereignty, or territorial ownership.

The command interface is available in English, Russian, Spanish, Brazilian Portuguese, Arabic, Indonesian, Hindi, and Turkish. Telegram locale hints drive onboarding recommendations, while the player's explicit language remains authoritative. The selectable catalog is the versioned ISO country/territory list plus explicitly supported Kosovo; search works across localized names and codes.

## Replays

The server first produces a result and ordered battle-event stream. The Mini App then presents those events as a top-down or isometric 2D replay. A separate renderer may later turn the same events into short video highlights.

This separation keeps the simulation authoritative and testable while allowing clients to improve animation independently.

## Economy

The initial economy contains four primary resources:

- Credits for modules, repairs, and campaign-asset production
- Research Points for technology and doctrine unlocks
- Materials for campaign production and selected upgrades
- XP for commander progression

The intended monetization boundary is convenience and cosmetics. Direct sale of unbeatable combat power, unlimited paid battle energy, hidden odds, and irreversible loss of purchased personal units are outside the product principles.

## MVP Scope

The recommended first release includes Telegram registration, alliance selection, onboarding, a compact unit catalog, several terrain types, three combat-group presets, deterministic personal operations, basic modules and research, campaign-asset production, one-versus-one weekly alliance battles, bot results, Mini App replays, PostgreSQL persistence, Docker deployment, basic admin controls, metrics, and audit logging.

Advanced diplomacy, a continuous global map, multi-alliance theaters, deep social systems, and automated weekly video can follow after the daily and weekly loops demonstrate retention.

## Success Criteria

The MVP is successful when a new player can complete onboarding and a first battle quickly, a normal battle takes no more than a few decisions to launch, identical simulation inputs reproduce identical results, small alliances remain useful, campaign rewards cannot be duplicated by retries, and the weekly outcome clearly explains both alliance and personal contribution.
