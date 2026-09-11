# ADR 0006 Spatial Personal Battles

## Status

Accepted

## Context

Engine version 3 reduced a combat group to aggregate power, tactic-fit, counter, and terrain bonuses. That made equipment composition relevant but did not represent position, weapon range, line of sight, movement, or control of ground. The command-only interface must add spatial decisions without turning a 1–3 minute operation into manual control of every unit on every step.

Aircraft must not attack the entire battlefield merely because they are airborne. Indirect fire may cross terrain that blocks direct fire, while still requiring reconnaissance. Important objects must take sustained presence to capture and must remain possible to recapture.

## Decision

- Introduce deterministic spatial battles on versioned axial hex maps. Engine version 5 expands the field to 9×12 and sends a pre-rendered square tactical map image from the same catalog snapshot used by simulation.
- Ship three initial map templates for mountain, river/coastal, and open terrain. Biome-specific base terrain and explicit overrides define roads, forests, hills, mountains, water, and swamps.
- Give every unit a snapshotted movement profile, movement points, weapon range, minimum range, sight range, and fire mode.
- Direct fire requires an unobstructed line. Mountains, hills, and forests can block it. Artillery has finite 2–5 range, crosses line-of-sight blockers, and requires a friendly observer. Attack aircraft and fighters move in an air layer and have finite movement and weapon range.
- Let the player choose active group → map entry → first objective → tactic. After the first objective, surviving units choose remaining uncaptured objectives.
- Treat tactics as behavior only. They alter route preference, hold behavior, unit order, and target priority; engine v4 applies no tactic-fit, counter-order, or aggregate terrain power bonus.
- Resolve movement, fire, capture, destruction, and rout into typed spatial events. Persist the full map, player and enemy snapshots, deployment choices, objective state, and event stream.
- Capture requires two uninterrupted steps by one side's operational ground units. Empty or contested steps reset progress. An already owned objective changes hands only after the opposing side completes the same capture process.
- End a battle when one side controls every important objective or the opposing army has no combat-capable units. A 48-step safety boundary converts the losing force into a routed army using objective control and surviving strength, preventing an unrecoverable simulation loop.
- Keep engine version 3 replay logic available for historical battles.

## 2026-09-11 amendment

Personal engine version 8 prevents mutual-ambush deadlocks: an ambush formation may wait in cover near an unseen enemy, but it resumes movement every third step while no target is available. The stored event `side` remains the acting side, including for destruction events; localized reports must therefore name it as the attacker. Turn-limit results explicitly say that the trailing army withdrew, and surviving routed equipment is not described as destroyed.

Personal engine version 9 caps catalog movement at four road hexes per step, while guaranteeing one-cell progress through any terrain the unit profile can traverse. A defensive formation no longer remains stationary on a controlled objective when an enemy can fire on it and it has no available target in return. Arsenal detail cards show the current wallet, and army equipment controls retain a fixed minus/plus column pair by using inert callbacks for unavailable actions.

## Consequences

The active group now has observable movement, range, reconnaissance, terrain, and target-selection behavior. A deployment route can win or lose an otherwise equal battle, and captured ground can change hands during the same operation.

Telegram adds two pre-battle decisions but does not require per-step input. The stored spatial event stream is suitable for a later animated or Mini App replay renderer. The command-only slice uses an immutable square PNG sector map and a summarized battle report.

Future weekly mass combat may reuse map topology, terrain, objectives, and event vocabulary, but it must use formation-scale actors and a separate ruleset. Personal owned units must not be reused as destructible campaign assets.
