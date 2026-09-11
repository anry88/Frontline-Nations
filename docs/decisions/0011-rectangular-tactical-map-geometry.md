# ADR 0011: Rectangular Tactical-Map Geometry

## Context

The first graphical renderer treated rectangular `width × height` catalogs as axial-coordinate parallelograms and then rotated the assembled board. Telegram received a square file, but the playable field appeared skewed and stretched. Repeated base terrain made maps tactically opaque, enemy entries reused one `E` marker, some entries appeared away from the visible edge after rotation, and transparent atlas margins exposed dark seams between hexes.

## Decision

- New map version 4 uses `ODD_R_OFFSET`: every row contains exactly `width` columns, alternate rows are offset by half a hex, and the visible board is an upright rectangle.
- `BattleMapDefinition` owns adjacency, distance, and line interpolation for its declared layout. Legacy snapshots without `gridLayout` continue to default to axial geometry; personal engine v6 and weekly engine v3 use the new offset geometry.
- A deterministic catalog generator produces 24 distinct personal layouts and ten distinct weekly layouts from explicit battlefield/biome identities, edge-entry anchors, objective roles, and connected road graphs. Stable seeded variation fills coherent terrain regions between those authored anchors.
- All player and enemy entries must occupy unique boundary cells. Images label the sides with blue A–C and red X–Z badges placed outside the playable edge.
- The renderer seals generated art inside exact opaque hex masks, overlaps placement by a pixel, draws narrow roads toward actual neighboring road cells, keeps the board unrotated, and exports a fixed 1,536×1,536 PNG. Dim half-hexes outside the rectangle remain non-playable visual plugs.
- Every personal map must expose at least four terrain types; every weekly map must expose at least four terrain types and exactly five suitable objectives.
- Map version 5 replaces the remaining shared anchor template with explicitly authored anchors for every battlefield. Personal sectors have unique three-objective layouts and at least eight deployment-edge patterns; weekly maps have unique five-objective layouts across six front orientations. Roads attach boundary entries to their nearest objectives, span the objective graph, and add deterministic alternate links.

## Consequences

- The image now matches movement, range, line of sight, and pathfinding rather than presenting a cosmetic projection of different geometry.
- Map assets are larger and the generator takes longer, but the committed PNGs remain cacheable and Telegram never renders a map at request time.
- Old resolved snapshots retain their stored geometry and engine version. Open weekly snapshots are refreshed from current map version 5 during normal campaign recovery.
