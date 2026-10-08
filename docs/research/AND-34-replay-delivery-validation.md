# AND-34: durable replay delivery validation

Status: complete. Implementation, automated/backend verification, and Telegram presentation contracts pass. Separate Android and iOS builds are not applicable to this command-only bot.

## Behavior

- A personal or manually requested weekly replay callback persists one job per replay kind, resource, and player.
- The callback is answered before the replay job is created, and FFmpeg work runs on a separate one-thread executor after the surrounding game transaction commits.
- Repeated clicks while queued or rendering reuse the existing job. A click after readiness or delivery requests another delivery without creating another render when the cache remains valid.
- `initializing`, `queued`, `rendering`, `ready`, `delivered`, and `failed` are durable states. Interrupted `initializing`/`rendering` rows return to `queued` at application start with a new technical attempt.
- A render failure becomes `failed` and sends a localized safe-retry button. A transient Telegram failure leaves the job `ready`, creates a new bounded technical attempt, and retries after configurable backoff. Permanent rejection or exhaustion becomes `failed`.
- Every delivery calls `ReplayService` again. A retained artifact is a cache hit; an expired file is regenerated from the stored battle snapshots/events before Telegram receives a fresh URL.
- The worker processes at most four jobs per wake on one thread. `frontline.replay.queue.pending` and `frontline.replay.queue.active` expose queue pressure alongside existing process CPU, heap, HTTP, and technical-stage panels.

No numeric ETA is shown because the available data does not yet support a stable estimate.

## Verification

Automated coverage includes:

- two concurrent requests sharing one durable job and one initial technical attempt;
- cache-hit readiness and Telegram acceptance;
- FFmpeg/render failure with explicit retry;
- transient Telegram send failure followed by a successful user retry;
- restart recovery of an interrupted render;
- repeat delivery re-preparing the artifact so cache expiry can rebuild it;
- expired personal cache returning no file before a successful rebuild;
- current replay authorization/signature and retention tests;
- all eight locale copy/button contracts, including Arabic mixed-direction fragments.

Commands:

```shell
./gradlew test
./gradlew bootJar
```

The full suite passed with 164 tests, 0 failures, 0 errors, and 0 skipped. A clean PostgreSQL 16.15 instance validated and applied Flyway migrations V1–V26, the packaged application reached `Started`, and `/actuator/health` returned `UP`.

## Client scope

The bot has one server build. The narrow Telegram Desktop observation in `docs/research/AND-32-mobile-telegram-qa.md` confirms that the existing square replay presentation works in a Telegram client, while automated tests cover the new queued/preparing/ready/retry states and delivery behavior. A platform-specific Android or iOS pass is required only when a reproducible Telegram-client difference is reported; it is not a completion gate for AND-34.
