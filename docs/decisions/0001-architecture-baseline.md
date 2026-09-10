# ADR 0001 Modular Monolith Architecture Baseline

- Status: Accepted
- Date: 2026-09-11

## Context

Frontline Nations needs Telegram-facing interfaces, persistent progression, deterministic personal battles, aggregate weekly campaigns, replay delivery, scheduled state transitions, and operational tooling. The product loop is not yet validated, and the initial team benefits from simple local development and transactional consistency.

## Decision

Build the first working product as a Kotlin and Spring Boot modular monolith backed by PostgreSQL. Keep domain boundaries explicit inside one deployable backend. Treat the replay/video renderer as a separate optional process because its Chromium and FFmpeg workload is operationally distinct.

Battle calculation remains server-authoritative and versioned. Clients and renderers consume persisted battle events and never determine results.

## Consequences

- Cross-domain transactions and deployment remain simple during MVP development.
- Module APIs and ownership still need discipline to prevent a tightly coupled codebase.
- PostgreSQL migrations, job idempotency, and deterministic simulation tests are required from the beginning.
- Services may be extracted later when measured scaling, isolation, or ownership needs justify the operational cost.
