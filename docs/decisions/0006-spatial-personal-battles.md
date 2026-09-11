# ADR 0006 Spatial Personal Battles

## Status

Accepted

## Context

Engine version 3 reduced a combat group to aggregate power, tactic-fit, counter, and terrain bonuses. That made equipment composition relevant but did not represent position, weapon range, line of sight, movement, or control of ground. The command-only interface must add spatial decisions without turning a 1–3 minute operation into manual control of every unit on every step.

Aircraft must not attack the entire battlefield merely because they are airborne. Indirect fire may cross terrain that blocks direct fire, while still requiring reconnaissance. Important objects must take sustained presence to capture and must remain possible to recapture.

## Decision

- Introduce deterministic engine version 4 on versioned 7×7 axial hex maps. Hexes are an internal movement graph; the Telegram briefing renders them as a compact tactical sector map rather than a chess board.
- Ship three initial map templates for mountain, river/coastal, and open terrain. Biome-specific base terrain and explicit overrides define roads, forests, hills, mountains, water, and swamps.
- Give every unit a snapshotted movement profile, movement points, weapon range, minimum range, sight range, and fire mode.
- Direct fire requires an unobstructed line. Mountains, hills, and forests can block it. Artillery has finite 2–5 range, crosses line-of-sight blockers, and requires a friendly observer. Attack aircraft and fighters move in an air layer and have finite movement and weapon range.
- Let the player choose active group → map entry → first objective → tactic. After the first objective, surviving units choose remaining uncaptured objectives.
- Treat tactics as behavior only. They alter route preference, hold behavior, unit order, and target priority; engine v4 applies no tactic-fit, counter-order, or aggregate terrain power bonus.
- Resolve movement, fire, capture, destruction, and rout into typed spatial events. Persist the full map, player and enemy snapshots, deployment choices, objective state, and event stream.
- Capture requires two uninterrupted steps by one side's operational ground units. Empty or contested steps reset progress. An already owned objective changes hands only after the opposing side completes the same capture process.
- End a battle when one side controls every important objective or the opposing army has no combat-capable units. A 24-step safety boundary converts the losing force into a routed army using objective control and surviving strength, preventing an unrecoverable simulation loop.
- Keep engine version 3 replay logic available for historical battles.

## Consequences

The active group now has observable movement, range, reconnaissance, terrain, and target-selection behavior. A deployment route can win or lose an otherwise equal battle, and captured ground can change hands during the same operation.

Telegram adds two pre-battle decisions but does not require per-step input. The stored spatial event stream is suitable for a later image or Mini App replay renderer. The first command-only slice uses a text sector map and a summarized battle report.

Future weekly mass combat may reuse map topology, terrain, objectives, and event vocabulary, but it must use formation-scale actors and a separate ruleset. Personal owned units must not be reused as destructible campaign assets.
