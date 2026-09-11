# Frontline Nations Product Overview

Frontline Nations is a planned asynchronous multiplayer strategy game designed around Telegram. It combines quick personal battles with a longer collective campaign: every short session develops the player's commander and can strengthen an alliance in a weekly war.

## Core Loop

A player opens the bot, reviews the state of the current campaign, and chooses from several operations tied to named real-world landscapes. Before battle, the player selects a saved combat group and one tactic. The server resolves the encounter immediately and returns rewards and an optional replay.

The command-only MVP now implements this strategic slice: five operation offers drawn from 24 named battlefields expose different intelligence and reward multipliers. Each operation uses an individually composed, upright rectangular 9×12 offset-hex sector with coherent terrain regions, three distinct edge entries, connected roads, and three illustrated important objectives. The bot sends a pre-rendered square map rather than a text diagram. The player assigns the active group an entry, a first objective, and a behavior doctrine. Units then move, spot, fire at finite ranges, capture objectives over consecutive steps, and can contest or retake them. Full graphical replay playback remains planned.

Between battles, players can buy or craft seven personal equipment classes in batches, select them into one of three CP-limited presets, and upgrade individual units through five levels. Every commander level automatically adds 1 CP, from 10 CP at level 1 to the supported 1,000 CP ceiling. Research Points and manual capacity purchases are retired. Destroyed equipment is permanently removed from usable inventory, while survivors return after personal and weekly battles. Modules, branching technologies, repairs, and doctrine perks remain planned.

## Strategic Depth

Combat groups are constrained by Command Points rather than a simple slot count. The six personal-operation categories are 10–25, 26–50, 51–100, 101–250, 251–500, and 501–1,000 deployed CP. Heavy armor, artillery, reconnaissance, aircraft, and air defense compete for the same budget. Every class has different terrain movement, sight, range, minimum range, and fire rules. Direct fire needs line of sight, artillery may fire indirectly when allies spot its target, and aircraft still have finite movement and attack range. Tactics are visible behavioral orders—route preference, holding, movement order, and target priority—rather than hidden percentage bonuses. Large forces are simulated as class-and-level formations so tactical maps stay readable and deterministic.

Higher tiers should broaden specialization rather than provide unconditional percentage upgrades. Commander progression unlocks options gradually, while seasonal prestige emphasizes recognition and cosmetics instead of endless power growth.

## Weekly Alliance War

The campaign follows a weekly rhythm:

- Monday: match alliances of similar active strength and announce battlefield conditions.
- Monday–Saturday: run personal operations, produce assets, and contribute to the alliance.
- Saturday: publish an incomplete reconnaissance summary.
- Sunday: lock contributions, resolve the aggregate battle, publish replay/highlights, and award results.

The command-only MVP implements a 250-country ranked weekly front: 125 adjacent pairings, Sunday 15:00 Belgrade resolution, a random 10–25 CP NPC equipment group for every country, reserved player equipment with deterministic casualties, ten individually composed pre-rendered 15×21 offset-hex maps, five illustrated capture points, contributor rewards, and Telegram notifications. Visual replay remains planned.

The design supports many country- and territory-named alliances without allowing population alone to decide every campaign. Matchmaking, NPC garrisons, underdog factors, contribution caps, and dynamic shortage bonuses are planned balancing tools.

Names, flags, and map locations are neutral fictional-game identifiers. They do not express a claim about recognition, sovereignty, or territorial ownership.

The command interface is available in English, Russian, Spanish, Brazilian Portuguese, Arabic, Indonesian, Hindi, and Turkish. Telegram locale hints drive onboarding recommendations, while the player's explicit language remains authoritative. The selectable catalog is the versioned ISO country/territory list plus explicitly supported Kosovo; search works across localized names and codes.

## Replays

The server first produces a result and ordered battle-event stream. The Mini App then presents those events as a top-down or isometric 2D replay. A separate renderer may later turn the same events into short video highlights.

This separation keeps the simulation authoritative and testable while allowing clients to improve animation independently.

## Economy

The current economy contains three primary resources:

- Credits for equipment replacement, purchases, crafting, and future modules
- Materials for campaign production and selected upgrades
- XP for commander progression

The intended monetization boundary is convenience and cosmetics. Direct sale of unbeatable combat power, paid battle energy, and hidden odds remain outside the product principles. Equipment can be lost in combat, so replacement income must remain earnable through normal play and the explicit daily reward.

## MVP Scope

The recommended first release includes Telegram registration, alliance selection, onboarding, a compact unit catalog, several terrain types, three combat-group presets, deterministic personal operations, basic modules and research, campaign-asset production, one-versus-one weekly alliance battles, bot results, Mini App replays, PostgreSQL persistence, Docker deployment, basic admin controls, metrics, and audit logging.

Advanced diplomacy, a continuous global map, multi-alliance theaters, deep social systems, and automated weekly video can follow after the daily and weekly loops demonstrate retention.

## Success Criteria

The MVP is successful when a new player can complete onboarding and a first battle quickly, a normal battle takes no more than a few decisions to launch, identical simulation inputs reproduce identical results, small alliances remain useful, campaign rewards cannot be duplicated by retries, and the weekly outcome clearly explains both alliance and personal contribution.
