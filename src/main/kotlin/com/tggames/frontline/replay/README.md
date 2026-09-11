# Replay module

This adapter turns finalized battle data into Telegram-playable MP4 files. It must remain downstream of the battle engines: renderers may interpolate saved movement and visualize saved fire, damage, destruction, and objective events, but they must never recalculate an outcome.

`BattleReplayRenderer` projects stored odd-row map coordinates onto the immutable square map PNG and composes BGR frames with stable unit icons. `ReplayVideoEncoder` streams those frames to FFmpeg and writes H.264/YUV420 MP4 at the configured 3 FPS default. `ReplayService` enforces personal/alliance access before rendering, serializes concurrent requests per battle, maintains the expiring versioned local cache, and signs public fetch URLs. `ReplayController` serves only already-generated files with a valid HMAC token. Weekly outbox delivery reuses one cached artifact for every reachable player in the two participating countries.

When event or snapshot fields change, bump the relevant battle engine version and keep Jackson defaults for older JSON. Add renderer regression coverage for every new visible event type. Rendered videos are runtime cache artifacts and must never be committed.
