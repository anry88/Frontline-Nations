# Battle Map Assets

`source/terrain-hex-atlas.png` and `source/strategic-object-atlas.png` are generated production sources. `scripts/render-battle-map-assets.py` crops and normalizes them, derives terrain-aware road junctions and transitions, and writes ready-to-send square maps under `src/main/resources/static/assets/maps/`.

Run:

```bash
python3 scripts/generate-map-catalogs.py
python3 scripts/render-battle-map-assets.py
```

The script requires Python 3 and Pillow.

The terrain atlas was generated as a transparent 5×2 sheet in this exact order: plain, road, forest, hill, mountain, water, swamp, desert, tundra, coast. The prompt requested hand-painted top-down pointy hexes, consistent scale and lighting, restrained natural colors, no units, labels, flags, UI, or text.

The objective atlas was generated as a transparent 3×2 sheet in this exact order: communications tower, reinforced crossing, supply depot, command post, radar station, airfield. The prompt requested top-down isolated military-strategy objects matching the terrain atlas, no real-world insignia, labels, people, flags, UI, or text.

The version-5 catalog generator preserves explicit battlefield identities and uses authored tactical anchors for every named map. The 24 personal sectors have unique objective layouts and at least eight deployment-edge patterns; the ten weekly fields each have a unique five-objective layout and use six different front orientations. A deterministic graph attaches every entry to its nearest objective, spans all objectives, and adds alternate links so roads follow each map's geometry instead of converging on one repeated center template. Stable seeds fill biome-appropriate terrain around those anchors.

The renderer is deterministic. It clips every generated texture to an exact full-bleed hex mask, slightly overlaps adjacent blocks to eliminate transparent seams, joins roads from their real offset-grid neighbors, uses the surrounding biome below roads, orients shores toward nearby water, rejects incompatible terrain transitions, overlays objective objects, labels the opposing entry sets A–C and X–Z outside their edge cells, and adds a non-playable dim half-hex rim. It does not rotate or shear the field: every output is an upright 1,536×1,536 PNG whose visible board is a true rectangle.
