# Battle Map Assets

`source/terrain-hex-atlas.png` and `source/strategic-object-atlas.png` are generated production sources. `scripts/render-battle-map-assets.py` crops and normalizes them, derives terrain-aware road junctions and transitions, and writes ready-to-send square maps under `src/main/resources/static/assets/maps/`.

Run:

```bash
python3 scripts/render-battle-map-assets.py
```

The script requires Python 3 and Pillow.

The terrain atlas was generated as a transparent 5×2 sheet in this exact order: plain, road, forest, hill, mountain, water, swamp, desert, tundra, coast. The prompt requested hand-painted top-down pointy hexes, consistent scale and lighting, restrained natural colors, no units, labels, flags, UI, or text.

The objective atlas was generated as a transparent 3×2 sheet in this exact order: communications tower, reinforced crossing, supply depot, command post, radar station, airfield. The prompt requested top-down isolated military-strategy objects matching the terrain atlas, no real-world insignia, labels, people, flags, UI, or text.

The renderer is deterministic. It varies repeated textures by coordinate, joins roads from their actual axial neighbors, uses the surrounding biome below roads, orients shores toward water, rejects incompatible terrain transitions, overlays objective objects, adds a non-playable dim half-hex rim, rotates the board for a compact composition, and pads the result to an exact square.
