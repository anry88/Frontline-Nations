# ADR 0009: Graphical Hex-Map Delivery

Partially superseded by [ADR 0011](0011-rectangular-tactical-map-geometry.md) for coordinate layout, map composition, edge markers, and seam handling.

## Status

Accepted

## Decision

Personal battle maps are 9×12 and weekly battle maps are 15×21. Their axial catalogs remain authoritative for movement and combat. A deterministic build script normalizes generated terrain and strategic-object atlases, derives connected road junctions, creates natural shoreline and feature transitions, places each objective on suitable terrain, and pre-renders immutable square PNGs.

Telegram sends these images during personal deployment and `/front`; it no longer sends text map diagrams. Player entries, enemy entries, and numbered objectives are embedded in the image. The dim half-hex ring is visual-only and lies outside the authoritative map dimensions, so units cannot enter it.

Personal combat uses engine version 5 and at most 48 steps. Weekly combat retains engine version 2 and uses at most 96 turns. Unresolved weekly rows receive refreshed current map snapshots without changing pairings or contributions.

## Consequences

- The bot and battle engine share stable map identifiers and coordinates while image delivery remains cacheable.
- Roads connect according to actual neighboring road cells, including turns and junctions; their ground texture inherits the surrounding biome.
- Coast blocks face adjacent water, incompatible desert/tundra/coast transitions fail generation, and mountains or other features blend into the local base terrain.
- Signal towers and radar use elevated sites, crossings use road/bridge sites, and airfields use flat terrain. Each objective type has a generated visual block.
- Source atlases and the reproducible rendering script are versioned; generated map PNGs are committed because Telegram must receive ready-made images.
