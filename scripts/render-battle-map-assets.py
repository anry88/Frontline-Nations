#!/usr/bin/env python3
"""Build normalized generated hex tiles and immutable PNG battle maps."""

from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ATLAS = ROOT / "assets/maps/source/terrain-hex-atlas.png"
OBJECT_ATLAS = ROOT / "assets/maps/source/strategic-object-atlas.png"
STATIC = ROOT / "src/main/resources/static/assets/maps"
TILE_DIR = STATIC / "tiles"
PERSONAL_DIR = STATIC / "personal"
WEEKLY_DIR = STATIC / "weekly"
TERRAIN_ORDER = ["PLAIN", "ROAD", "FOREST", "HILL", "MOUNTAIN", "WATER", "SWAMP", "DESERT", "TUNDRA", "COAST"]
OBJECT_ORDER = ["communications", "crossing", "depot", "command", "radar", "airfield"]
TILE_SIZE = (72, 82)
X_STEP = 70
Y_STEP = 61
CANVAS_PADDING = 12
MAP_ORIGIN = TILE_SIZE[0] * 2 + CANVAS_PADDING
DIRECTIONS = ((1, 0), (1, -1), (0, -1), (-1, 0), (-1, 1), (0, 1))


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            pass
    return ImageFont.load_default()


def normalize_tiles() -> dict[str, Image.Image]:
    source = Image.open(ATLAS).convert("RGBA")
    tiles: dict[str, Image.Image] = {}
    TILE_DIR.mkdir(parents=True, exist_ok=True)
    for index, terrain in enumerate(TERRAIN_ORDER):
        column, row = index % 5, index // 5
        left = round(column * source.width / 5)
        right = round((column + 1) * source.width / 5)
        top = round(row * source.height / 2)
        bottom = round((row + 1) * source.height / 2)
        slot = source.crop((left, top, right, bottom))
        bounds = slot.getchannel("A").getbbox()
        if bounds is None:
            raise ValueError(f"Generated atlas slot {terrain} is empty")
        tile = slot.crop(bounds)
        tile.thumbnail((TILE_SIZE[0] - 2, TILE_SIZE[1] - 2), Image.Resampling.LANCZOS)
        normalized = Image.new("RGBA", TILE_SIZE)
        normalized.alpha_composite(tile, ((TILE_SIZE[0] - tile.width) // 2, (TILE_SIZE[1] - tile.height) // 2))
        normalized.save(TILE_DIR / f"{terrain.lower()}.png", optimize=True)
        tiles[terrain] = normalized
    return tiles


def normalize_objects() -> dict[str, Image.Image]:
    source = Image.open(OBJECT_ATLAS).convert("RGBA")
    objects: dict[str, Image.Image] = {}
    for index, name in enumerate(OBJECT_ORDER):
        column, row = index % 3, index // 3
        slot = source.crop((
            round(column * source.width / 3),
            round(row * source.height / 2),
            round((column + 1) * source.width / 3),
            round((row + 1) * source.height / 2),
        ))
        visible = slot.getchannel("A").point(lambda value: 255 if value > 24 else 0)
        bounds = visible.getbbox()
        if bounds is None:
            raise ValueError(f"Generated object atlas slot {name} is empty")
        sprite = slot.crop(bounds)
        sprite.thumbnail((64, 64), Image.Resampling.LANCZOS)
        normalized = Image.new("RGBA", (64, 64))
        normalized.alpha_composite(sprite, ((64 - sprite.width) // 2, (64 - sprite.height) // 2))
        normalized.save(TILE_DIR / f"objective-{name}.png", optimize=True)
        objects[name] = normalized
    return objects


def transformed(position: dict[str, int], width: int, height: int, variant: str) -> tuple[int, int]:
    q, r = position["q"], position["r"]
    if variant == "ROTATE_180":
        return width - 1 - q, height - 1 - r
    if variant == "MIRROR_HORIZONTAL":
        return width - 1 - q, r
    if variant == "MIRROR_VERTICAL":
        return q, height - 1 - r
    return q, r


def personal_maps() -> list[dict[str, Any]]:
    templates = json.loads((ROOT / "src/main/resources/catalog/battle-maps.json").read_text())
    assignments = json.loads((ROOT / "src/main/resources/catalog/battlefield-map-index.json").read_text())
    by_id = {item["id"]: item for item in templates}
    expanded = []
    for assignment in assignments:
        source = by_id[assignment["templateId"]]
        variant = assignment["variant"]
        base = source.get("biomeBaseTerrains", {}).get(assignment["biome"], source["baseTerrain"])
        item = dict(source)
        item.update(id=assignment["id"], version=3, baseTerrain=base, biomeBaseTerrains={})
        for field in ("cells", "playerEntries", "enemyEntries", "objectives"):
            values = []
            for value in source[field]:
                copy = dict(value)
                position = value.get("position", value)
                q, r = transformed(position, source["width"], source["height"], variant)
                if "position" in value:
                    copy["position"] = {"q": q, "r": r}
                else:
                    copy["q"], copy["r"] = q, r
                values.append(copy)
            item[field] = values
        if assignment["biome"] == "побережье":
            item["baseTerrain"] = "PLAIN"
            occupied = {(cell["q"], cell["r"]) for cell in item["cells"]}
            banks = set()
            for cell in item["cells"]:
                if cell["terrain"] != "WATER":
                    continue
                for dq, dr in DIRECTIONS:
                    position = (cell["q"] + dq, cell["r"] + dr)
                    if 0 <= position[0] < item["width"] and 0 <= position[1] < item["height"] and position not in occupied:
                        banks.add(position)
            item["cells"].extend({"q": q, "r": r, "terrain": "COAST"} for q, r in sorted(banks, key=lambda value: (value[1], value[0])))
        elif item["baseTerrain"] == "DESERT":
            item["cells"] = [cell for cell in item["cells"] if cell["terrain"] != "FOREST"]
        prepare_objective_sites(item)
        expanded.append(item)
    return expanded


def prepare_objective_sites(definition: dict[str, Any]) -> None:
    cells = {(cell["q"], cell["r"]): cell for cell in definition["cells"]}
    flat = definition["baseTerrain"] if definition["baseTerrain"] in {"PLAIN", "DESERT", "TUNDRA", "COAST"} else "PLAIN"
    for objective in definition["objectives"]:
        position = objective["position"]["q"], objective["position"]["r"]
        current = cells.get(position, {}).get("terrain", definition["baseTerrain"])
        key = objective["nameKey"]
        if key in {"objective_signal_tower", "objective_radar"}:
            terrain = "HILL"
        elif key in {"objective_central_crossing", "objective_north_crossing", "objective_south_crossing"}:
            terrain = "ROAD"
        elif key == "objective_airfield":
            terrain = flat
        elif key in {"objective_supply_depot", "objective_command_post"}:
            terrain = current if current == "ROAD" else flat
        else:
            terrain = current
        cells[position] = {"q": position[0], "r": position[1], "terrain": terrain}
    definition["cells"] = [cells[position] for position in sorted(cells, key=lambda value: (value[1], value[0]))]


def cell_xy(q: int, r: int) -> tuple[int, int]:
    """Project the engine's axial coordinates onto a pointy-top hex atlas."""
    return round((q + r / 2) * X_STEP), r * Y_STEP


def road_tile(background: Image.Image, connections: tuple[int, ...]) -> Image.Image:
    """Derive a connected road block for one of the 64 possible neighbor masks."""
    scale = 4
    tile = background.resize((TILE_SIZE[0] * scale, TILE_SIZE[1] * scale), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(tile)
    center = (TILE_SIZE[0] * scale // 2, TILE_SIZE[1] * scale // 2)
    for index in connections:
        dq, dr = DIRECTIONS[index]
        nx, ny = cell_xy(dq, dr)
        length = math.hypot(nx, ny)
        endpoint = (
            round(center[0] + nx / length * 39 * scale),
            round(center[1] + ny / length * 39 * scale),
        )
        draw.line((center, endpoint), fill=(190, 178, 151, 255), width=26 * scale)
        draw.line((center, endpoint), fill=(65, 69, 70, 255), width=19 * scale)
        draw.line((center, endpoint), fill=(218, 207, 153, 255), width=2 * scale)
    if connections:
        radius = 10 * scale
        draw.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=(65, 69, 70, 255))
    return tile.resize(TILE_SIZE, Image.Resampling.LANCZOS)


def coast_tile(tiles: dict[str, Image.Image], water_connections: tuple[int, ...]) -> Image.Image:
    """Compose a shoreline whose water half faces actual neighboring water cells."""
    if not water_connections:
        raise ValueError("A coast block must face at least one water block")
    vectors = []
    for index in water_connections:
        dq, dr = DIRECTIONS[index]
        vectors.append(cell_xy(dq, dr))
    vx = sum(vector[0] for vector in vectors)
    vy = sum(vector[1] for vector in vectors)
    length = math.hypot(vx, vy) or 1
    ux, uy = vx / length, vy / length
    mask = Image.new("L", TILE_SIZE)
    pixels = mask.load()
    center_x, center_y = TILE_SIZE[0] / 2, TILE_SIZE[1] / 2
    for y in range(TILE_SIZE[1]):
        for x in range(TILE_SIZE[0]):
            signed = (x - center_x) * ux + (y - center_y) * uy
            pixels[x, y] = max(0, min(255, round(128 + signed * 18)))
    transition = Image.composite(tiles["WATER"], tiles["PLAIN"], mask)
    rocks = tiles["COAST"].copy()
    rocks.putalpha(rocks.getchannel("A").point(lambda value: value * 3 // 10))
    transition.alpha_composite(rocks)
    return transition


def feature_transition_tile(background: Image.Image, feature: Image.Image) -> Image.Image:
    """Keep the neighbor-facing rim in the local biome while blending the feature into its center."""
    mask = Image.new("L", TILE_SIZE)
    pixels = mask.load()
    center_x, center_y = TILE_SIZE[0] / 2, TILE_SIZE[1] / 2
    for y in range(TILE_SIZE[1]):
        for x in range(TILE_SIZE[0]):
            radial = math.hypot((x - center_x) / center_x, (y - center_y) / center_y)
            pixels[x, y] = max(0, min(255, round((1.12 - radial) * 510)))
    return Image.composite(feature, background, mask)


def terrain_variant(tile: Image.Image, seed: int) -> Image.Image:
    variant = seed % 4
    if variant == 1:
        tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    elif variant == 2:
        tile = tile.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    elif variant == 3:
        tile = tile.transpose(Image.Transpose.FLIP_LEFT_RIGHT).transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    tile = ImageEnhance.Brightness(tile).enhance((96 + seed % 9) / 100)
    return ImageEnhance.Color(tile).enhance((96 + seed % 7) / 100)


def boundary_stub(tile: Image.Image, inward_connections: tuple[int, ...]) -> Image.Image:
    """Keep only the inward half of an out-of-bounds block and visibly dim it."""
    dimmed = ImageEnhance.Brightness(tile).enhance(0.48)
    mask = Image.new("L", TILE_SIZE)
    pixels = mask.load()
    center_x, center_y = TILE_SIZE[0] / 2, TILE_SIZE[1] / 2
    vectors = [cell_xy(*DIRECTIONS[index]) for index in inward_connections]
    for y in range(TILE_SIZE[1]):
        for x in range(TILE_SIZE[0]):
            keep = max((x - center_x) * vx + (y - center_y) * vy for vx, vy in vectors)
            pixels[x, y] = 170 if keep >= 0 else 0
    alpha = Image.new("L", TILE_SIZE)
    alpha_pixels = alpha.load()
    source_alpha = dimmed.getchannel("A").load()
    for y in range(TILE_SIZE[1]):
        for x in range(TILE_SIZE[0]):
            alpha_pixels[x, y] = source_alpha[x, y] * pixels[x, y] // 255
    dimmed.putalpha(alpha)
    return dimmed


def objective_sprite_name(name_key: str) -> str:
    if "crossing" in name_key:
        return "crossing"
    return {
        "objective_signal_tower": "communications",
        "objective_supply_depot": "depot",
        "objective_command_post": "command",
        "objective_radar": "radar",
        "objective_airfield": "airfield",
    }.get(name_key, "command")


def validate_natural_transitions(definition: dict[str, Any]) -> None:
    overrides = {(cell["q"], cell["r"]): cell["terrain"] for cell in definition["cells"]}
    terrain_at = lambda q, r: overrides.get((q, r), definition["baseTerrain"])
    forbidden = {frozenset(("DESERT", "TUNDRA")), frozenset(("DESERT", "COAST")), frozenset(("TUNDRA", "COAST"))}
    for r in range(definition["height"]):
        for q in range(definition["width"]):
            terrain = terrain_at(q, r)
            neighbors = [
                terrain_at(q + dq, r + dr)
                for dq, dr in DIRECTIONS
                if 0 <= q + dq < definition["width"] and 0 <= r + dr < definition["height"]
            ]
            if terrain == "COAST" and ("WATER" not in neighbors or not any(value not in {"WATER", "COAST"} for value in neighbors)):
                raise ValueError(f"{definition['id']} has a coast block without both water and land at {(q, r)}")
            for neighbor in neighbors:
                if frozenset((terrain, neighbor)) in forbidden:
                    raise ValueError(f"{definition['id']} has an unnatural {terrain}/{neighbor} transition at {(q, r)}")


def draw_marker(canvas: Image.Image, q: int, r: int, label: str, color: tuple[int, int, int, int]) -> None:
    draw = ImageDraw.Draw(canvas)
    x, y = cell_xy(q, r)
    center = (x + MAP_ORIGIN + TILE_SIZE[0] // 2, y + MAP_ORIGIN + TILE_SIZE[1] // 2)
    radius = 12
    draw.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=color, outline=(255, 255, 255, 245), width=2)
    label_font = font(14 if len(label) == 1 else 11)
    box = draw.textbbox((0, 0), label, font=label_font)
    draw.text((center[0] - (box[2] - box[0]) / 2, center[1] - (box[3] - box[1]) / 2 - 1), label, font=label_font, fill=(255, 255, 255, 255))


def render_map(definition: dict[str, Any], tiles: dict[str, Image.Image], objects: dict[str, Image.Image], output: Path) -> None:
    validate_natural_transitions(definition)
    width, height = definition["width"], definition["height"]
    canvas_width = round((width - 1 + (height - 1) / 2) * X_STEP) + TILE_SIZE[0]
    canvas_height = (height - 1) * Y_STEP + TILE_SIZE[1]
    canvas = Image.new("RGBA", (canvas_width + MAP_ORIGIN * 2, canvas_height + MAP_ORIGIN * 2), (15, 23, 31, 255))
    overrides = {(cell["q"], cell["r"]): cell["terrain"] for cell in definition["cells"]}
    road_positions = {position for position, terrain in overrides.items() if terrain == "ROAD"}
    boundary: dict[tuple[int, int], set[int]] = {}
    for r in range(height):
        for q in range(width):
            for index, (dq, dr) in enumerate(DIRECTIONS):
                neighbor = q + dq, r + dr
                if not (0 <= neighbor[0] < width and 0 <= neighbor[1] < height):
                    boundary.setdefault(neighbor, set()).add((index + 3) % 6)
    for (q, r), inward in boundary.items():
        x, y = cell_xy(q, r)
        seed = sum(definition["id"].encode("utf-8")) + q * 31 + r * 17
        stub = boundary_stub(terrain_variant(tiles[definition["baseTerrain"]], seed), tuple(sorted(inward)))
        canvas.alpha_composite(stub, (x + MAP_ORIGIN, y + MAP_ORIGIN))
    for r in range(height):
        for q in range(width):
            x, y = cell_xy(q, r)
            terrain = overrides.get((q, r), definition["baseTerrain"])
            tile = tiles[terrain]
            if terrain not in {definition["baseTerrain"], "ROAD", "WATER", "COAST"}:
                tile = feature_transition_tile(tiles[definition["baseTerrain"]], tile)
            if terrain == "COAST":
                water_connections = tuple(
                    index
                    for index, (dq, dr) in enumerate(DIRECTIONS)
                    if overrides.get((q + dq, r + dr), definition["baseTerrain"]) == "WATER"
                )
                tile = coast_tile(tiles, water_connections)
            if terrain == "ROAD":
                connections = tuple(
                    index
                    for index, (dq, dr) in enumerate(DIRECTIONS)
                    if (q + dq, r + dr) in road_positions
                )
                adjacent_terrain = [
                    overrides.get((q + dq, r + dr), definition["baseTerrain"])
                    for dq, dr in DIRECTIONS
                    if overrides.get((q + dq, r + dr), definition["baseTerrain"]) != "ROAD"
                ]
                counts = Counter(adjacent_terrain)
                background_name = "WATER" if counts["WATER"] >= 2 else max(counts, key=lambda name: (counts[name], name == definition["baseTerrain"]))
                tile = road_tile(tiles[background_name], connections)
                mask = sum(1 << index for index in connections)
                variant_path = TILE_DIR / f"road-{background_name.lower()}-{mask:02x}.png"
                if not variant_path.exists():
                    tile.save(variant_path, optimize=True)
            elif terrain != "COAST":
                stable_seed = sum(definition["id"].encode("utf-8")) + q * 31 + r * 17
                tile = terrain_variant(tile, stable_seed)
            canvas.alpha_composite(tile, (x + MAP_ORIGIN, y + MAP_ORIGIN))
    for objective in definition["objectives"]:
        x, y = cell_xy(objective["position"]["q"], objective["position"]["r"])
        sprite = objects[objective_sprite_name(objective["nameKey"])]
        canvas.alpha_composite(sprite, (x + MAP_ORIGIN + 4, y + MAP_ORIGIN + 9))
    for index, entry in enumerate(definition["playerEntries"]):
        draw_marker(canvas, entry["position"]["q"], entry["position"]["r"], chr(ord("A") + index), (35, 112, 218, 235))
    for entry in definition["enemyEntries"]:
        draw_marker(canvas, entry["position"]["q"], entry["position"]["r"], "E", (190, 49, 54, 235))
    for index, objective in enumerate(definition["objectives"]):
        draw_marker(canvas, objective["position"]["q"], objective["position"]["r"], str(index + 1), (193, 139, 23, 245))
    output.parent.mkdir(parents=True, exist_ok=True)
    rotated = canvas.rotate(-12, resample=Image.Resampling.BICUBIC, expand=True, fillcolor=(15, 23, 31, 255))
    background = Image.new("RGB", rotated.size, (15, 23, 31))
    content_bounds = ImageChops.difference(rotated.convert("RGB"), background).getbbox()
    if content_bounds is not None:
        margin = 28
        rotated = rotated.crop((
            max(0, content_bounds[0] - margin),
            max(0, content_bounds[1] - margin),
            min(rotated.width, content_bounds[2] + margin),
            min(rotated.height, content_bounds[3] + margin),
        ))
    side = max(rotated.width, rotated.height)
    square = Image.new("RGBA", (side, side), (15, 23, 31, 255))
    square.alpha_composite(rotated, ((side - rotated.width) // 2, (side - rotated.height) // 2))
    square.convert("RGB").quantize(colors=224, method=Image.Quantize.MEDIANCUT).save(output, optimize=True)


def main() -> None:
    TILE_DIR.mkdir(parents=True, exist_ok=True)
    for generated_variant in TILE_DIR.glob("road-*.png"):
        generated_variant.unlink()
    tiles = normalize_tiles()
    objects = normalize_objects()
    for definition in personal_maps():
        render_map(definition, tiles, objects, PERSONAL_DIR / f"{definition['id']}.png")
    weekly = json.loads((ROOT / "src/main/resources/catalog/weekly-battle-maps.json").read_text())
    for definition in weekly:
        prepare_objective_sites(definition)
        render_map(definition, tiles, objects, WEEKLY_DIR / f"{definition['id']}.png")
    print(f"Rendered {len(TERRAIN_ORDER)} terrain blocks, {len(objects)} objective blocks, 24 personal maps, and {len(weekly)} weekly maps")


if __name__ == "__main__":
    main()
