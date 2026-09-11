#!/usr/bin/env python3
"""Build normalized generated hex tiles and immutable PNG battle maps."""

from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageEnhance, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ATLAS = ROOT / "assets/maps/source/terrain-hex-atlas.png"
OBJECT_ATLAS = ROOT / "assets/maps/source/strategic-object-atlas.png"
STATIC = ROOT / "src/main/resources/static/assets/maps"
TILE_DIR = STATIC / "tiles"
PERSONAL_DIR = STATIC / "personal"
WEEKLY_DIR = STATIC / "weekly"
TERRAIN_ORDER = ["PLAIN", "ROAD", "FOREST", "HILL", "MOUNTAIN", "WATER", "SWAMP", "DESERT", "TUNDRA", "COAST"]
OBJECT_ORDER = ["communications", "crossing", "depot", "command", "radar", "airfield"]
SOURCE_TILE_SIZE = (96, 110)
CANVAS_SIZE = 1536
BOARD_TARGET = 1260
EVEN_DIRECTIONS = ((1, 0), (0, -1), (-1, -1), (-1, 0), (-1, 1), (0, 1))
ODD_DIRECTIONS = ((1, 0), (1, -1), (0, -1), (-1, 0), (0, 1), (1, 1))
TERRAIN_COLORS = {
    "PLAIN": (89, 112, 64, 255), "ROAD": (89, 112, 64, 255), "FOREST": (35, 76, 45, 255),
    "HILL": (106, 105, 69, 255), "MOUNTAIN": (103, 105, 103, 255), "WATER": (31, 91, 121, 255),
    "SWAMP": (63, 86, 61, 255), "DESERT": (167, 135, 79, 255), "TUNDRA": (175, 184, 174, 255),
    "COAST": (91, 118, 91, 255),
}


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


def hex_mask(size: tuple[int, int], inset: int = 0) -> Image.Image:
    width, height = size
    mask = Image.new("L", size)
    ImageDraw.Draw(mask).polygon(
        (
            (width // 2, inset),
            (width - 1 - inset, height // 4),
            (width - 1 - inset, height * 3 // 4),
            (width // 2, height - 1 - inset),
            (inset, height * 3 // 4),
            (inset, height // 4),
        ),
        fill=255,
    )
    return mask


def seal_hex(tile: Image.Image, terrain: str, size: tuple[int, int]) -> Image.Image:
    """Clip a slightly oversized generated texture to an exact full-bleed hex."""
    texture = Image.new("RGBA", size, TERRAIN_COLORS[terrain])
    visible = tile.getchannel("A").getbbox()
    source = tile.crop(visible) if visible else tile
    fitted = ImageOps.contain(source, (round(size[0] * 1.1), round(size[1] * 1.1)), Image.Resampling.LANCZOS)
    texture.alpha_composite(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    texture.putalpha(hex_mask(size))
    draw = ImageDraw.Draw(texture)
    draw.line(
        (
            (size[0] // 2, 0), (size[0] - 1, size[1] // 4), (size[0] - 1, size[1] * 3 // 4),
            (size[0] // 2, size[1] - 1), (0, size[1] * 3 // 4), (0, size[1] // 4), (size[0] // 2, 0),
        ),
        fill=(22, 31, 30, 105),
        width=max(1, size[0] // 64),
    )
    return texture


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
        normalized = seal_hex(tile, terrain, SOURCE_TILE_SIZE)
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


def personal_maps() -> list[dict[str, Any]]:
    definitions = json.loads((ROOT / "src/main/resources/catalog/battle-maps.json").read_text())
    assignments = json.loads((ROOT / "src/main/resources/catalog/battlefield-map-index.json").read_text())
    by_id = {definition["id"]: definition for definition in definitions}
    if set(by_id) != {assignment["id"] for assignment in assignments}:
        raise ValueError("Personal map definitions and battlefield assignments differ")
    ordered = [by_id[assignment["id"]] for assignment in assignments]
    for definition in ordered:
        prepare_objective_sites(definition)
    return ordered


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


def directions(row: int) -> tuple[tuple[int, int], ...]:
    return ODD_DIRECTIONS if row & 1 else EVEN_DIRECTIONS


def tile_geometry(width: int, height: int) -> tuple[tuple[int, int], int, int]:
    for tile_width in range(150, 47, -1):
        tile_height = round(tile_width * SOURCE_TILE_SIZE[1] / SOURCE_TILE_SIZE[0])
        x_step = tile_width - max(1, tile_width // 96)
        y_step = round(tile_height * 0.75) - max(1, tile_height // 110)
        board_width = (width - 1) * x_step + x_step // 2 + tile_width
        board_height = (height - 1) * y_step + tile_height
        if max(board_width, board_height) <= BOARD_TARGET:
            return (tile_width, tile_height), x_step, y_step
    raise ValueError(f"Cannot fit {width}×{height} map on the canvas")


def cell_xy(q: int, r: int, x_step: int, y_step: int) -> tuple[int, int]:
    """Project odd-row offset coordinates to an upright rectangular hex field."""
    return q * x_step + (x_step // 2 if r & 1 else 0), r * y_step


def neighbor_positions(q: int, r: int, width: int, height: int) -> list[tuple[int, int, int]]:
    return [
        (index, q + dq, r + dr)
        for index, (dq, dr) in enumerate(directions(r))
        if 0 <= q + dq < width and 0 <= r + dr < height
    ]


def road_tile(
    background: Image.Image,
    q: int,
    r: int,
    connections: tuple[int, ...],
    size: tuple[int, int],
    x_step: int,
    y_step: int,
) -> Image.Image:
    """Derive a connected road block for one of the 64 possible neighbor masks."""
    scale = 4
    tile = background.resize((size[0] * scale, size[1] * scale), Image.Resampling.LANCZOS)
    draw = ImageDraw.Draw(tile)
    center = (size[0] * scale // 2, size[1] * scale // 2)
    current_x, current_y = cell_xy(q, r, x_step, y_step)
    for index in connections:
        dq, dr = directions(r)[index]
        neighbor_x, neighbor_y = cell_xy(q + dq, r + dr, x_step, y_step)
        nx, ny = neighbor_x - current_x, neighbor_y - current_y
        length = math.hypot(nx, ny)
        endpoint = (
            round(center[0] + nx / length * max(size) * 0.58 * scale),
            round(center[1] + ny / length * max(size) * 0.58 * scale),
        )
        draw.line((center, endpoint), fill=(165, 151, 121, 255), width=max(7, round(size[0] * 0.15)) * scale)
        draw.line((center, endpoint), fill=(58, 62, 61, 255), width=max(5, round(size[0] * 0.10)) * scale)
        draw.line((center, endpoint), fill=(221, 205, 142, 230), width=max(1, round(size[0] * 0.014)) * scale)
    if connections:
        radius = max(4, round(size[0] * 0.055)) * scale
        draw.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=(58, 62, 61, 255))
    result = tile.resize(size, Image.Resampling.LANCZOS)
    result.putalpha(hex_mask(size))
    return result


def coast_tile(
    tiles: dict[str, Image.Image],
    q: int,
    r: int,
    water_positions: tuple[tuple[int, int], ...],
    size: tuple[int, int],
    x_step: int,
    y_step: int,
) -> Image.Image:
    """Compose a shoreline whose water half faces actual neighboring water cells."""
    if not water_positions:
        raise ValueError("A coast block must face at least one water block")
    vectors = []
    current_x, current_y = cell_xy(q, r, x_step, y_step)
    for water_q, water_r in water_positions:
        neighbor_x, neighbor_y = cell_xy(water_q, water_r, x_step, y_step)
        vectors.append((neighbor_x - current_x, neighbor_y - current_y))
    vx = sum(vector[0] for vector in vectors)
    vy = sum(vector[1] for vector in vectors)
    length = math.hypot(vx, vy) or 1
    ux, uy = vx / length, vy / length
    mask = Image.new("L", size)
    pixels = mask.load()
    center_x, center_y = size[0] / 2, size[1] / 2
    for y in range(size[1]):
        for x in range(size[0]):
            signed = (x - center_x) * ux + (y - center_y) * uy
            pixels[x, y] = max(0, min(255, round(128 + signed * 12)))
    water = tiles["WATER"].resize(size, Image.Resampling.LANCZOS)
    plain = tiles["PLAIN"].resize(size, Image.Resampling.LANCZOS)
    transition = Image.composite(water, plain, mask)
    rocks = tiles["COAST"].resize(size, Image.Resampling.LANCZOS)
    rocks.putalpha(rocks.getchannel("A").point(lambda value: value * 3 // 10))
    transition.alpha_composite(rocks)
    transition.putalpha(hex_mask(size))
    return transition


def feature_transition_tile(background: Image.Image, feature: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Keep the neighbor-facing rim in the local biome while blending the feature into its center."""
    mask = Image.new("L", size)
    pixels = mask.load()
    center_x, center_y = size[0] / 2, size[1] / 2
    for y in range(size[1]):
        for x in range(size[0]):
            radial = math.hypot((x - center_x) / center_x, (y - center_y) / center_y)
            pixels[x, y] = max(0, min(255, round((1.12 - radial) * 510)))
    result = Image.composite(feature.resize(size, Image.Resampling.LANCZOS), background.resize(size, Image.Resampling.LANCZOS), mask)
    result.putalpha(hex_mask(size))
    return result


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


def boundary_stub(tile: Image.Image, inward_vectors: tuple[tuple[int, int], ...], size: tuple[int, int]) -> Image.Image:
    """Keep only the inward half of an out-of-bounds block and visibly dim it."""
    dimmed = ImageEnhance.Brightness(tile.resize(size, Image.Resampling.LANCZOS)).enhance(0.48)
    mask = Image.new("L", size)
    pixels = mask.load()
    center_x, center_y = size[0] / 2, size[1] / 2
    for y in range(size[1]):
        for x in range(size[0]):
            keep = max((x - center_x) * vx + (y - center_y) * vy for vx, vy in inward_vectors)
            pixels[x, y] = 170 if keep >= 0 else 0
    alpha = Image.new("L", size)
    alpha_pixels = alpha.load()
    source_alpha = dimmed.getchannel("A").load()
    for y in range(size[1]):
        for x in range(size[0]):
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
                terrain_at(neighbor_q, neighbor_r)
                for _, neighbor_q, neighbor_r in neighbor_positions(q, r, definition["width"], definition["height"])
            ]
            if terrain == "COAST":
                nearby = {
                    (second_q, second_r)
                    for _, neighbor_q, neighbor_r in neighbor_positions(q, r, definition["width"], definition["height"])
                    for _, second_q, second_r in neighbor_positions(neighbor_q, neighbor_r, definition["width"], definition["height"])
                }
                nearby_terrains = neighbors + [terrain_at(*value) for value in nearby]
                if "WATER" not in nearby_terrains or not any(value not in {"WATER", "COAST"} for value in nearby_terrains):
                    raise ValueError(f"{definition['id']} has a coast block without nearby water and land at {(q, r)}")
            for neighbor in neighbors:
                if frozenset((terrain, neighbor)) in forbidden:
                    raise ValueError(f"{definition['id']} has an unnatural {terrain}/{neighbor} transition at {(q, r)}")


def hex_polygon(left: int, top: int, size: tuple[int, int]) -> tuple[tuple[int, int], ...]:
    width, height = size
    return (
        (left + width // 2, top), (left + width - 1, top + height // 4),
        (left + width - 1, top + height * 3 // 4), (left + width // 2, top + height - 1),
        (left, top + height * 3 // 4), (left, top + height // 4),
    )


def draw_entry_marker(
    canvas: Image.Image,
    definition: dict[str, Any],
    position: dict[str, int],
    label: str,
    color: tuple[int, int, int, int],
    origin: tuple[int, int],
    size: tuple[int, int],
    x_step: int,
    y_step: int,
) -> None:
    draw = ImageDraw.Draw(canvas)
    q, r = position["q"], position["r"]
    x, y = cell_xy(q, r, x_step, y_step)
    cell_center = (origin[0] + x + size[0] // 2, origin[1] + y + size[1] // 2)
    if q == 0:
        vector = (-1, 0)
    elif q == definition["width"] - 1:
        vector = (1, 0)
    elif r == 0:
        vector = (0, -1)
    elif r == definition["height"] - 1:
        vector = (0, 1)
    else:
        raise ValueError(f"{definition['id']} entry {label} is not on the map edge")
    offset = round(max(size) * 0.62)
    center = (cell_center[0] + vector[0] * offset, cell_center[1] + vector[1] * offset)
    radius = max(16, round(size[0] * 0.20))
    draw.line((center, cell_center), fill=color, width=max(5, size[0] // 15))
    draw.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=color, outline=(245, 249, 246, 255), width=max(2, size[0] // 32))
    label_font = font(max(18, round(size[0] * 0.22)))
    box = draw.textbbox((0, 0), label, font=label_font)
    draw.text((center[0] - (box[2] - box[0]) / 2, center[1] - (box[3] - box[1]) / 2 - 2), label, font=label_font, fill=(255, 255, 255, 255))


def render_map(definition: dict[str, Any], tiles: dict[str, Image.Image], objects: dict[str, Image.Image], output: Path) -> None:
    validate_natural_transitions(definition)
    width, height = definition["width"], definition["height"]
    if definition.get("gridLayout") != "ODD_R_OFFSET":
        raise ValueError(f"{definition['id']} must use the rectangular ODD_R_OFFSET grid")
    size, x_step, y_step = tile_geometry(width, height)
    board_width = (width - 1) * x_step + x_step // 2 + size[0]
    board_height = (height - 1) * y_step + size[1]
    origin = ((CANVAS_SIZE - board_width) // 2, (CANVAS_SIZE - board_height) // 2)
    canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (13, 22, 29, 255))
    frame = ImageDraw.Draw(canvas)
    frame.rounded_rectangle(
        (origin[0] - 42, origin[1] - 42, origin[0] + board_width + 42, origin[1] + board_height + 42),
        radius=28,
        fill=(18, 30, 37, 255),
        outline=(65, 82, 85, 255),
        width=3,
    )
    overrides = {(cell["q"], cell["r"]): cell["terrain"] for cell in definition["cells"]}
    road_positions = {position for position, terrain in overrides.items() if terrain == "ROAD"}
    boundary: dict[tuple[int, int], list[tuple[int, int]]] = {}
    for r in range(height):
        for q in range(width):
            current_x, current_y = cell_xy(q, r, x_step, y_step)
            for dq, dr in directions(r):
                neighbor = q + dq, r + dr
                if not (0 <= neighbor[0] < width and 0 <= neighbor[1] < height):
                    neighbor_x, neighbor_y = cell_xy(neighbor[0], neighbor[1], x_step, y_step)
                    boundary.setdefault(neighbor, []).append((current_x - neighbor_x, current_y - neighbor_y))
    for (q, r), inward_vectors in boundary.items():
        x, y = cell_xy(q, r, x_step, y_step)
        seed = sum(definition["id"].encode("utf-8")) + q * 31 + r * 17
        stub = boundary_stub(terrain_variant(tiles[definition["baseTerrain"]], seed), tuple(inward_vectors), size)
        canvas.alpha_composite(stub, (x + origin[0], y + origin[1]))
    for r in range(height):
        for q in range(width):
            x, y = cell_xy(q, r, x_step, y_step)
            terrain = overrides.get((q, r), definition["baseTerrain"])
            tile = tiles[terrain].resize(size, Image.Resampling.LANCZOS)
            if terrain not in {definition["baseTerrain"], "ROAD", "WATER", "COAST"}:
                tile = feature_transition_tile(tiles[definition["baseTerrain"]], tiles[terrain], size)
            if terrain == "COAST":
                immediate = {
                    (neighbor_q, neighbor_r)
                    for _, neighbor_q, neighbor_r in neighbor_positions(q, r, width, height)
                }
                nearby = immediate | {
                    (second_q, second_r)
                    for neighbor_q, neighbor_r in immediate
                    for _, second_q, second_r in neighbor_positions(neighbor_q, neighbor_r, width, height)
                }
                water_positions = tuple(
                    value for value in nearby if overrides.get(value, definition["baseTerrain"]) == "WATER"
                )
                tile = coast_tile(tiles, q, r, water_positions, size, x_step, y_step)
            if terrain == "ROAD":
                connections = tuple(
                    index
                    for index, (dq, dr) in enumerate(directions(r))
                    if (q + dq, r + dr) in road_positions
                )
                adjacent_terrain = [
                    overrides.get((q + dq, r + dr), definition["baseTerrain"])
                    for dq, dr in directions(r)
                    if 0 <= q + dq < width and 0 <= r + dr < height
                    if overrides.get((q + dq, r + dr), definition["baseTerrain"]) != "ROAD"
                ]
                counts = Counter(adjacent_terrain)
                background_name = definition["baseTerrain"] if not counts else ("WATER" if counts["WATER"] >= 2 else max(counts, key=lambda name: (counts[name], name == definition["baseTerrain"])))
                tile = road_tile(tiles[background_name], q, r, connections, size, x_step, y_step)
            elif terrain != "COAST":
                stable_seed = sum(definition["id"].encode("utf-8")) + q * 31 + r * 17
                tile = terrain_variant(tile, stable_seed)
                tile.putalpha(hex_mask(size))
            canvas.alpha_composite(tile, (x + origin[0], y + origin[1]))
    for objective in definition["objectives"]:
        x, y = cell_xy(objective["position"]["q"], objective["position"]["r"], x_step, y_step)
        left, top = x + origin[0], y + origin[1]
        ImageDraw.Draw(canvas).line(hex_polygon(left + 2, top + 2, (size[0] - 4, size[1] - 4)) + (hex_polygon(left + 2, top + 2, (size[0] - 4, size[1] - 4))[0],), fill=(238, 184, 53, 230), width=max(3, size[0] // 30))
        sprite = objects[objective_sprite_name(objective["nameKey"])].copy()
        sprite.thumbnail((round(size[0] * 0.64), round(size[1] * 0.64)), Image.Resampling.LANCZOS)
        canvas.alpha_composite(sprite, (left + (size[0] - sprite.width) // 2, top + (size[1] - sprite.height) // 2))
    for index, entry in enumerate(definition["playerEntries"]):
        draw_entry_marker(canvas, definition, entry["position"], chr(ord("A") + index), (31, 113, 216, 245), origin, size, x_step, y_step)
    for index, entry in enumerate(definition["enemyEntries"]):
        draw_entry_marker(canvas, definition, entry["position"], chr(ord("X") + index), (198, 55, 60, 245), origin, size, x_step, y_step)
    for index, objective in enumerate(definition["objectives"]):
        x, y = cell_xy(objective["position"]["q"], objective["position"]["r"], x_step, y_step)
        center = (origin[0] + x + round(size[0] * 0.76), origin[1] + y + round(size[1] * 0.24))
        radius = max(14, round(size[0] * 0.15))
        marker = ImageDraw.Draw(canvas)
        marker.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=(198, 139, 24, 255), outline=(255, 247, 216, 255), width=max(2, size[0] // 36))
        label_font = font(max(16, round(size[0] * 0.18)))
        label = str(index + 1)
        box = marker.textbbox((0, 0), label, font=label_font)
        marker.text((center[0] - (box[2] - box[0]) / 2, center[1] - (box[3] - box[1]) / 2 - 2), label, font=label_font, fill=(255, 255, 255, 255))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").quantize(colors=240, method=Image.Quantize.MEDIANCUT).save(output, optimize=True)


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
