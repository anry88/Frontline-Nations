# AND-32: Telegram battle-path QA

Status: in progress. This record separates observed client behavior from automated localization checks and from the current-build Telegram smoke-test.

## Build and client scope

- Local build under test: `0.1.0`, based on Git commit `adf87fe` plus the uncommitted AND-24–AND-36 changes in the current working tree.
- Observed live client: Telegram Desktop for macOS, narrowed to 900×1200 physical pixels. Telegram switched to its single-pane layout; on this Retina display that is approximately a 450×600 logical-pixel content window.
- Observed live bot: `@frontline_nations_bot`, Russian owner account, existing private-chat history.
- The live bot still shows the pre-AND-28–AND-31 production flow. It is evidence for the old path only; it does not verify the current local guided onboarding, compact result, primary next action, or recovery planner.
- The command-only bot has one server build. Separate Android and iOS builds and a mandatory platform matrix are outside scope. A platform-specific pass is needed only for a reproducible client difference.

## Observed old-path pass

The existing Russian chat history contains a complete first/repeat-battle sequence from 30 September 2026. It was reviewed in the narrow single-pane layout without sending new commands or changing player state.

| Step | Evidence observed | Result |
|---|---|---|
| Ready army and offer | Active preset, CP, five operation buttons | Pass for old RU path |
| Map and entry | 1536×1536 square map, entry buttons A/B/C | Pass for old RU path; full map requires scrolling because the caption occupies most of the short viewport |
| Objective and tactic | Three objectives, five tactics, two-column tactic rows | Pass for old RU path |
| Result and losses | Full battle report and permanent losses | Old result is long and does not expose the new primary next action |
| Replay | MP4 animation, 20 seconds, square presentation | Pass for old RU path |
| Second battle | A second offer and battle are present in the same session | Pass for old RU path |
| Recovery | Repeated army screens after CP dropped below the minimum | Old recovery requires manual additions; current AND-30 flow is not deployed |
| Front | Weekly map, group orders, and front controls | Pass for old RU path |

At the narrow width, Russian buttons remain tappable and their labels are not visibly clipped. The square map remains recognizable, but only its lower strip is visible when the long photo caption and keyboard are aligned in the short viewport; the player must scroll upward to inspect the full map.

## Current-build localization contract

Automated checks cover EN, RU, ES, PT-BR (`pt`), AR, ID, HI, and TR for:

- critical first-mission, battle-result, replay, recovery, and front labels;
- one-column, two-column, tactic, and language-selector label budgets for a narrow Telegram keyboard;
- critical message/caption payload limits and unresolved placeholders;
- Arabic copy containing Latin commands, CP/XP/Credits, level tokens, and numbers.

The Telegram delivery adapter now wraps left-to-right technical fragments in Unicode direction isolates whenever the surrounding text contains Arabic. Callback payloads remain byte-for-byte unchanged.

Validation command:

```shell
./gradlew test
```

Current repository result after the later AND-36 additions: 164 tests passed; 0 failures, 0 errors, and 0 skipped. `git diff --check` also passed.

## Matrix

| Locale | Automated current-build copy/button contract | Live Telegram client |
|---|---:|---:|
| EN | Pass | Pending current build |
| RU | Pass | Pass for old path; pending current build |
| ES | Pass | Pending current build |
| PT-BR | Pass | Pending current build |
| AR | Pass, including RTL isolation | Pending current build |
| ID | Pass | Pending current build |
| HI | Pass | Pending current build |
| TR | Pass | Pending current build |

## Remaining acceptance work

AND-32 remains open until the current build is available in the accessible Telegram client and every locale completes the actual `/start` → first battle → replay → second battle/recovery → `/front` path. Record the Telegram client version, approximate viewport, build revision, anonymized screenshots, and reproduction steps. A platform-specific rerun is added only if this pass exposes or a player reports a client-dependent defect.
