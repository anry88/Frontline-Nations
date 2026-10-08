# AND-32: Telegram battle-path QA

Status: completed on 2026-10-08. The current production build was checked in the accessible Telegram client and the only observed narrow-layout defect was fixed and retested.

## Build and client scope

- Production revision: `b27cfdb` (`frontline-nations:selector-b27cfdb`).
- Observed client: Telegram Desktop for macOS, narrowed to 908×1207 physical pixels. Telegram used its single-pane layout; on this Retina display the content area is approximately 454×604 logical pixels.
- Observed bot: `@frontline_nations_bot`, existing owner account in a private chat.
- The command-only bot has one server build. There are no Android or iOS application builds to test. A platform-specific pass is needed only for a reproducible Telegram-client difference.
- The owner account was restored to Russian after the locale checks. No operation was selected, no battle was started, and no wallet or inventory state changed.

## Live current-build pass

The production client was switched through EN, RU, ES, PT-BR (`pt`), AR, ID, HI, and TR. In every locale the current build returned the localized `/start` profile, `/battle` offer list, and `/front` weekly map and state. Arabic rendered right-to-left while technical fragments such as the nickname, CP, XP, Credits, dates, and numbers retained readable left-to-right direction. Russian was selected again at the end.

The same chat contains a complete battle and replay sequence, including the square MP4, a repeat battle, an undersized-army state, and weekly-front controls. Current automated contracts cover the guided first-mission card, compact result, replay queue/retry states, primary next action, recovery planner, contribution review, callback sizes, and Telegram payload limits. The existing progressed owner account cannot re-enter first-account onboarding without altering production data, so the live current-build command pass and deterministic automated coverage are the reproducible evidence for those branches. Five new external-player sessions belong to AND-27 and are not an acceptance dependency for this client-layout QA task.

## Finding and correction

The two-column language selector clipped `🇮🇩 Bahasa Indonesia` at the tested narrow width. Revision `b27cfdb` introduced a selector-only label, `🇮🇩 Indonesia`, while keeping `Bahasa Indonesia` in confirmation and current-language text. The regression contract now validates the rendered selector label against an 18-code-point budget and explicitly preserves the full native name.

After production deployment, the selector was opened again at the same width. `🇮🇩 Indonesia` rendered without clipping; selecting it produced `Bahasa diubah ke Bahasa Indonesia` and `Saat ini: Bahasa Indonesia`; switching back to Russian succeeded.

## Verification matrix

| Locale | Automated copy/button contract | Live `/start`, `/battle`, `/front` | Narrow selector |
|---|---:|---:|---:|
| EN | Pass | Pass | Pass |
| RU | Pass | Pass | Pass |
| ES | Pass | Pass | Pass |
| PT-BR | Pass | Pass | Pass |
| AR | Pass, including RTL isolation | Pass | Pass |
| ID | Pass | Pass | Pass after `b27cfdb` |
| HI | Pass | Pass | Pass |
| TR | Pass | Pass | Pass |

## Automated and production validation

```shell
./gradlew test
docker --context hdc build --platform linux/amd64 --tag frontline-nations:selector-b27cfdb .
curl https://frontline-nations.tg-games.com/health
```

- Local suite: 164 tests passed; 0 failures, 0 errors, 0 skipped.
- Docker image build: successful; the Dockerfile repeated the full test suite.
- Production container: `frontline-nations:selector-b27cfdb`, healthy.
- Flyway: 26 migrations validated; schema already at V26.
- Public health: HTTP 200 with `{"status":"UP"}`.
- `git diff --check`: passed.

No current-build client-layout acceptance work remains for AND-32. Future platform-specific testing is triggered by a reproducible client report rather than by separate game builds.
