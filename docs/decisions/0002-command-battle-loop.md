# ADR 0002 Command Battle Loop

## Status

Accepted

## Context

The first MVP resolved `/battle` immediately. That proved persistence and deterministic rewards, but it did not prove the product's central claim that terrain, intelligence, risk, and tactical choice can make a short Telegram session strategically interesting.

The product specification calls for three operation offers, incomplete intelligence, a pre-battle tactical order, 8–15 logical rounds, and an explainable result. Mini App development is explicitly deferred, so the complete interaction must fit Telegram commands and inline buttons.

## Decision

- `/battle` generates exactly three deterministic offers bound to player ID, game date, and remaining Combat Orders.
- Every board contains one scouted, one standard, and one risky operation with 90%, 100%, and 140% reward multipliers.
- Selecting an offer reveals its available intelligence and five tactical orders.
- Selecting a tactic resolves engine version 2 in 8–12 logical rounds.
- Enemy archetype and biome modify tactical effectiveness; the result reports those modifiers.
- A stale callback cannot spend a Combat Order after the player's order count changes.
- The completed battle seed, metadata, and ordered events are persisted for reproducibility, audit, and a future replay surface.
- Research Points, commander levels, wins, losses, and streaks make the immediate result visible in `/profile`.

## Consequences

The bot now tests meaningful choice without introducing combat-group inventory or a Mini App. Offers can be regenerated rather than stored because their inputs and secret-derived randomness are stable. Historical verification requires retaining engine version 2 code and the battle metadata stored by migration V2.

Balance values remain code-backed for this narrow slice. Moving these values into versioned catalog data is required before content operators need live balancing.
