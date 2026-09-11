# ADR 0013: Event-log video replays

Status: Accepted

## Context

The command-only Telegram experience needs an understandable post-battle playback without introducing a Mini App. Personal battles already persist movement, fire, damage, destruction, routing, and capture events. Weekly engine v4 lacked stable formation identity, starting positions, movement, and hit events, so an accurate aggregate replay could not be reconstructed.

## Decision

The authoritative engines finish and persist results before any visual work begins. Weekly engine v5 adds stable formation IDs and replay-grade movement/hit fields; it does not change renderer authority. The backend loads the stored map, group/formation snapshots, and ordered events, projects their offset coordinates onto the same authored PNG, and composes square Java2D frames. FFmpeg encodes those frames as silent H.264/YUV420 MP4 suitable for Telegram animation playback.

Rendering is lazy after a player presses the replay callback and synchronized by battle ID. Personal access requires battle ownership; weekly access requires membership in either participating country. The generated file lives in a bounded local cache, while a truncated HMAC token derived from the server battle salt protects the public media URL. The public endpoint cannot trigger rendering.

## Consequences

- A replay visualizes the exact saved outcome and never reruns random or tactical rules.
- Personal results and resolved `/front` views can offer playback without a web client.
- FFmpeg becomes a runtime image dependency, and first playback has render latency.
- Cache loss is harmless because an authorized callback regenerates the file.
- Old weekly results without v5 replay fields remain readable as text but cannot produce an accurate video.
- The renderer can later move to a worker without changing the event contract or Telegram callback surface.
