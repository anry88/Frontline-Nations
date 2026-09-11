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

The catalog generator preserves explicit battlefield identities, edge entries, objective roles, and road connections while using stable seeds for biome-appropriate regions. It writes 24 distinct 9×12 personal layouts and ten distinct 15×21 weekly layouts on an odd-row offset grid.

The renderer is deterministic. It clips every generated texture to an exact full-bleed hex mask, slightly overlaps adjacent blocks to eliminate transparent seams, joins roads from their real offset-grid neighbors, uses the surrounding biome below roads, orients shores toward nearby water, rejects incompatible terrain transitions, overlays objective objects, labels the opposing entry sets A–C and X–Z outside their edge cells, and adds a non-playable dim half-hex rim. It does not rotate or shear the field: every output is an upright 1,536×1,536 PNG whose visible board is a true rectangle.
