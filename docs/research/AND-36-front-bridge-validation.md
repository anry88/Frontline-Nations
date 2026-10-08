# AND-36: personal battle to weekly front bridge

## Product decision

The initial bridge appears once after the second completed personal battle. This is a deliberately conservative competence gate: the player has already completed the first guided mission and one repeat battle before weekly reservation and permanent-loss rules are introduced. AND-27 must validate whether two battles is the right threshold; the gate is centralized in `FrontBridgePolicy.MIN_COMPLETED_BATTLES` for later tuning.

The bridge is withheld when the player has already contributed or has already received it. It reads the current campaign record rather than projecting a result. The message shows the player's country and opponent, contribution deadline or current status, both sides' current contributed power, configured base rewards, winner bonus duration, concrete-unit reservation, explicit withdrawal, survivor return and permanent loss. It explicitly says that contribution cannot guarantee victory and that NPC forces compensate smaller sides.

## Safety and existing contracts

The bridge recommends `Choose a front group` only while contributions are open and at least two free battle-ready presets exist. Otherwise its single action opens the front overview and tells the player to prepare another group or wait.

Selecting entry, objective and tactic no longer commits immediately. A final review shows:

- exact preset composition and CP;
- selected entry, first objective and tactic;
- withdrawal deadline and post-lock state;
- concrete reservation and permanent-loss consequences;
- whether another free battle-ready preset remains.

If none remains, army setup is the primary button and `Reserve anyway` is a separate warning action. No equipment is ever placed automatically. The existing `CampaignService.contribute` transaction remains authoritative for week locks, concrete owned-unit IDs, unique preset membership, replacement, withdrawal and idempotent reservation.

## Measurement

V26 stores one offer per player with separate `shown_at` and `clicked_at`. `shown_at` and `front_bridge_shown` are written only after Telegram accepts the message; neither is a read receipt. The bounded product gauge reports distinct external players for:

- bridge shown;
- bridge clicked;
- contribution committed after the bridge;
- contribution withdrawn after the bridge;
- a later user session after the bridge;
- a personal battle blocked by front-reserved equipment.

The configured internal owner accounts remain excluded. Telegram IDs, sessions and battle IDs stay out of metric labels.

## Validation

Targeted policy, deferred-delivery event, artificial-cohort metric and eight-locale mobile-contract tests cover the gate, safe recommendation, delivery semantics, conversion/guardrail counts, label width and message payload limits. The full suite passed with 164 tests, 0 failures, 0 errors and 0 skipped. `bootJar`, dashboard JSON validation and `git diff --check` also passed.

A clean PostgreSQL 16.15 instance validated and applied Flyway migrations V1–V26. The packaged application started successfully and `/actuator/health` returned `UP`; the temporary application process and database container were then stopped and removed.

Live Android/iOS presentation remains part of AND-32. The bridge was not sent through the currently open production Telegram bot because that bot is running an older deployment and a live send would not validate the local build.
