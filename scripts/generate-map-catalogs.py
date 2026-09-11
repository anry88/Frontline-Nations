#!/usr/bin/env python3
"""Generate deterministic, tactically varied rectangular battle-map catalogs.

The generator keeps the authored map identity, biome, entry sides, objective roles,
and road network explicit. Seeded terrain variation only fills the space between
those gameplay anchors; generated JSON remains the runtime source of truth.
"""

from __future__ import annotations

import hashlib
import heapq
import json
import math
import random
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "src/main/resources/catalog"
PERSONAL_PATH = CATALOG / "battle-maps.json"
INDEX_PATH = CATALOG / "battlefield-map-index.json"
WEEKLY_PATH = CATALOG / "weekly-battle-maps.json"

EVEN_DIRECTIONS = ((1, 0), (0, -1), (-1, -1), (-1, 0), (-1, 1), (0, 1))
ODD_DIRECTIONS = ((1, 0), (1, -1), (0, -1), (-1, 0), (0, 1), (1, 1))
BLOCKED = {"MOUNTAIN", "WATER"}


def seeded(identifier: str) -> random.Random:
    seed = int.from_bytes(hashlib.sha256(f"frontline-map-v4:{identifier}".encode()).digest()[:8], "big")
    return random.Random(seed)


def directions(row: int) -> tuple[tuple[int, int], ...]:
    return ODD_DIRECTIONS if row & 1 else EVEN_DIRECTIONS


def neighbors(position: tuple[int, int], width: int, height: int) -> list[tuple[int, int]]:
    q, r = position
    return [
        (q + dq, r + dr)
        for dq, dr in directions(r)
        if 0 <= q + dq < width and 0 <= r + dr < height
    ]


def axial(position: tuple[int, int]) -> tuple[int, int]:
    q, r = position
    return q - (r - (r & 1)) // 2, r


def distance(first: tuple[int, int], second: tuple[int, int]) -> int:
    aq, ar = axial(first)
    bq, br = axial(second)
    return max(abs(aq - bq), abs(ar - br), abs((-aq - ar) - (-bq - br)))


def paint_blob(
    grid: dict[tuple[int, int], str],
    center: tuple[int, int],
    radius: int,
    terrain: str,
    rng: random.Random,
) -> None:
    width = max(q for q, _ in grid) + 1
    height = max(r for _, r in grid) + 1
    for position in grid:
        d = distance(position, center)
        if d < radius or (d == radius and rng.random() < 0.62):
            grid[position] = terrain
    # Break up round procedural edges with a few neighbor-scale notches.
    for _ in range(max(1, radius)):
        position = (rng.randrange(width), rng.randrange(height))
        if distance(position, center) <= radius + 1 and rng.random() < 0.7:
            grid[position] = terrain


def add_blobs(
    grid: dict[tuple[int, int], str],
    terrain: str,
    count: int,
    radius: tuple[int, int],
    rng: random.Random,
    margin: int = 1,
) -> None:
    width = max(q for q, _ in grid) + 1
    height = max(r for _, r in grid) + 1
    for _ in range(count):
        center = (rng.randrange(margin, max(margin + 1, width - margin)), rng.randrange(margin, max(margin + 1, height - margin)))
        paint_blob(grid, center, rng.randint(*radius), terrain, rng)


def add_vertical_channel(grid: dict[tuple[int, int], str], terrain: str, rng: random.Random, thickness: int = 1) -> None:
    width = max(q for q, _ in grid) + 1
    height = max(r for _, r in grid) + 1
    phase = rng.random() * math.tau
    center = width // 2 + rng.choice((-1, 0, 0, 1))
    for r in range(height):
        q = round(center + math.sin(phase + r / 3.1) * min(2, width // 5))
        for offset in range(-(thickness - 1), thickness):
            if 0 <= q + offset < width:
                grid[q + offset, r] = terrain


def add_coast(grid: dict[tuple[int, int], str], rng: random.Random) -> None:
    width = max(q for q, _ in grid) + 1
    height = max(r for _, r in grid) + 1
    phase = rng.random() * math.tau
    water: set[tuple[int, int]] = set()
    for r in range(height):
        shore = round(width * 0.66 + math.sin(phase + r / 3.4) * 1.25)
        shore = max(width // 2 + 1, min(width - 2, shore))
        for q in range(shore + 1, width):
            grid[q, r] = "WATER"
            water.add((q, r))
    coast = {
        position
        for water_cell in water
        for position in neighbors(water_cell, width, height)
        if position not in water
    }
    # Concave shoreline turns can create a beach cell surrounded only by water
    # and other beach cells. Leave those as land so every rendered coast tile
    # visibly bridges water and inland terrain.
    coast -= {
        position
        for position in coast
        if not any(neighbor not in water and neighbor not in coast for neighbor in neighbors(position, width, height))
    }
    for position in coast:
        grid[position] = "COAST"


def base_and_theme(biome: str, identifier: str) -> tuple[str, str]:
    if identifier == "grand-river-crossing" or biome == "речная долина":
        return "PLAIN", "river"
    if identifier == "coastal-breach" or biome == "побережье":
        return "PLAIN", "coast"
    if identifier == "highland-corridor" or biome in {"горы", "холмистая местность"}:
        return "HILL", "highland"
    if identifier == "desert-trident" or biome == "пустыня":
        return "DESERT", "desert"
    if identifier == "forest-basin" or biome in {"лес", "джунгли"}:
        return "PLAIN", "forest"
    if identifier == "tundra-line" or biome == "тундра":
        return "TUNDRA", "tundra"
    if identifier == "marsh-causeway" or biome == "болота":
        return "PLAIN", "marsh"
    if identifier == "canyon-network":
        return "DESERT", "canyon"
    if identifier == "industrial-front":
        return "PLAIN", "industrial"
    return "PLAIN", "steppe"


def terrain_grid(identifier: str, biome: str, width: int, height: int) -> tuple[str, dict[tuple[int, int], str]]:
    base, theme = base_and_theme(biome, identifier)
    grid = {(q, r): base for r in range(height) for q in range(width)}
    rng = seeded(identifier)
    scale = 2 if width >= 15 else 1
    if theme == "river":
        add_blobs(grid, "FOREST", 3 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "SWAMP", 2 * scale, (1, 2), rng)
        add_vertical_channel(grid, "WATER", rng, 2 if width >= 15 else 1)
    elif theme == "coast":
        add_blobs(grid, "FOREST", 3 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "HILL", 2 * scale, (1, 2), rng)
        add_coast(grid, rng)
    elif theme == "highland":
        add_blobs(grid, "PLAIN", 3 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "FOREST", 2 * scale, (1, 2), rng)
        add_blobs(grid, "MOUNTAIN", 3 * scale, (1, 2), rng)
    elif theme == "desert":
        add_blobs(grid, "HILL", 4 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "MOUNTAIN", 2 * scale, (1, 2), rng)
    elif theme == "forest":
        add_blobs(grid, "FOREST", 7 * scale, (1, 3 + scale), rng)
        add_blobs(grid, "HILL", 2 * scale, (1, 2), rng)
        add_blobs(grid, "SWAMP", 2 * scale, (1, 2), rng)
    elif theme == "tundra":
        add_blobs(grid, "HILL", 4 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "MOUNTAIN", 2 * scale, (1, 2), rng)
        add_blobs(grid, "PLAIN", 2 * scale, (1, 2), rng)
    elif theme == "marsh":
        add_blobs(grid, "SWAMP", 7 * scale, (1, 3 + scale), rng)
        add_blobs(grid, "FOREST", 3 * scale, (1, 2 + scale), rng)
        add_vertical_channel(grid, "WATER", rng)
    elif theme == "canyon":
        add_blobs(grid, "HILL", 5 * scale, (1, 3), rng)
        for r in range(1, height - 1):
            for center in (width // 3, width * 2 // 3):
                q = center + round(math.sin((r + center) / 3) * 1.5)
                if 0 <= q < width and r % 5 not in {0, 1}:
                    grid[q, r] = "MOUNTAIN"
    else:
        add_blobs(grid, "FOREST", 3 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "HILL", 3 * scale, (1, 2 + scale), rng)
        add_blobs(grid, "SWAMP", 1 * scale, (1, 2), rng)
        if theme == "industrial":
            add_blobs(grid, "PLAIN", 3 * scale, (2, 3), rng)
    return base, grid


def shortest_path(
    grid: dict[tuple[int, int], str],
    start: tuple[int, int],
    goal: tuple[int, int],
    width: int,
    height: int,
) -> list[tuple[int, int]]:
    costs = {start: 0}
    previous: dict[tuple[int, int], tuple[int, int]] = {}
    frontier: list[tuple[int, int, tuple[int, int]]] = [(distance(start, goal), 0, start)]
    while frontier:
        _, cost, current = heapq.heappop(frontier)
        if current == goal:
            break
        if cost != costs[current]:
            continue
        for next_position in neighbors(current, width, height):
            terrain = grid[next_position]
            terrain_cost = {"WATER": 10, "MOUNTAIN": 7, "SWAMP": 4, "FOREST": 3, "HILL": 2}.get(terrain, 1)
            candidate = cost + terrain_cost
            if candidate < costs.get(next_position, 1_000_000):
                costs[next_position] = candidate
                previous[next_position] = current
                heapq.heappush(frontier, (candidate + distance(next_position, goal), candidate, next_position))
    if goal not in costs:
        raise ValueError(f"No road path from {start} to {goal}")
    result = [goal]
    while result[-1] != start:
        result.append(previous[result[-1]])
    return list(reversed(result))


def add_roads(
    grid: dict[tuple[int, int], str],
    connections: Iterable[tuple[tuple[int, int], tuple[int, int]]],
    width: int,
    height: int,
) -> None:
    for start, goal in connections:
        for position in shortest_path(grid, start, goal, width, height):
            grid[position] = "ROAD"


def cells_for(grid: dict[tuple[int, int], str], base: str) -> list[dict[str, Any]]:
    elevation = {"HILL": 1, "MOUNTAIN": 3}
    return [
        {"q": q, "r": r, "terrain": terrain, **({"elevation": elevation[terrain]} if terrain in elevation else {})}
        for (q, r), terrain in sorted(grid.items(), key=lambda item: (item[0][1], item[0][0]))
        if terrain != base
    ]


def position(value: tuple[int, int]) -> dict[str, int]:
    return {"q": value[0], "r": value[1]}


def personal_definition(assignment: dict[str, str]) -> dict[str, Any]:
    identifier, biome = assignment["id"], assignment["biome"]
    width, height = 9, 12
    base, grid = terrain_grid(identifier, biome, width, height)
    rng = seeded(f"anchors:{identifier}")
    entries = [(4, 11), (0, 7 + rng.randrange(0, 2)), (8, 8 + rng.randrange(0, 2))]
    enemies = [(4, 0), (0, 2 + rng.randrange(0, 2)), (8, 2 + rng.randrange(0, 2))]
    objectives = [
        (2 + rng.randrange(0, 2), 3 + rng.randrange(0, 2)),
        (4, 6),
        (5 + rng.randrange(0, 2), 8 + rng.randrange(0, 2)),
    ]
    connections = [
        (entries[0], objectives[1]), (entries[1], objectives[0]), (entries[2], objectives[2]),
        (objectives[0], objectives[1]), (objectives[1], objectives[2]),
        (objectives[0], enemies[1]), (objectives[1], enemies[0]), (objectives[2], enemies[2]),
    ]
    add_roads(grid, connections, width, height)
    return {
        "id": identifier,
        "version": 4,
        "nameKey": {
            "горы": "map_mountain_pass", "холмистая местность": "map_mountain_pass",
            "речная долина": "map_river_valley", "болота": "map_river_valley", "побережье": "map_river_valley",
        }.get(biome, "map_open_front"),
        "biomes": [biome],
        "width": width,
        "height": height,
        "gridLayout": "ODD_R_OFFSET",
        "baseTerrain": base,
        "cells": cells_for(grid, base),
        "playerEntries": [
            {"id": "S", "nameKey": "entry_south_road", "position": position(entries[0])},
            {"id": "W", "nameKey": "entry_west_road", "position": position(entries[1])},
            {"id": "E", "nameKey": "entry_east_route", "position": position(entries[2])},
        ],
        "enemyEntries": [
            {"id": "N", "nameKey": "entry_north", "position": position(enemies[0])},
            {"id": "NW", "nameKey": "entry_west_road", "position": position(enemies[1])},
            {"id": "NE", "nameKey": "entry_east_route", "position": position(enemies[2])},
        ],
        "objectives": [
            {"id": "signal", "nameKey": "objective_signal_tower", "position": position(objectives[0]), "captureSteps": 2},
            {"id": "crossing", "nameKey": "objective_central_crossing", "position": position(objectives[1]), "captureSteps": 2},
            {"id": "depot", "nameKey": "objective_supply_depot", "position": position(objectives[2]), "captureSteps": 2},
        ],
    }


WEEKLY_THEMES = {
    "grand-river-crossing": "речная долина",
    "highland-corridor": "горы",
    "desert-trident": "пустыня",
    "forest-basin": "лес",
    "coastal-breach": "побережье",
    "tundra-line": "тундра",
    "marsh-causeway": "болота",
    "steppe-encirclement": "степь",
    "canyon-network": "пустыня",
    "industrial-front": "равнина",
}


def weekly_definition(source: dict[str, Any]) -> dict[str, Any]:
    identifier = source["id"]
    width, height = 15, 21
    base, grid = terrain_grid(identifier, WEEKLY_THEMES[identifier], width, height)
    rng = seeded(f"anchors:{identifier}")
    player_rows = [3 + rng.randrange(0, 2), 9 + rng.randrange(0, 3), 16 + rng.randrange(0, 2)]
    enemy_rows = [3 + rng.randrange(0, 2), 9 + rng.randrange(0, 3), 16 + rng.randrange(0, 2)]
    entries = [(0, row) for row in player_rows]
    enemies = [(14, row) for row in enemy_rows]
    objectives = [
        (4 + rng.randrange(0, 2), 4 + rng.randrange(0, 2)),
        (9 + rng.randrange(0, 2), 5 + rng.randrange(0, 2)),
        (7, 10),
        (4 + rng.randrange(0, 2), 14 + rng.randrange(0, 2)),
        (9 + rng.randrange(0, 2), 15 + rng.randrange(0, 2)),
    ]
    connections = [
        (entries[0], objectives[0]), (entries[1], objectives[2]), (entries[2], objectives[3]),
        (objectives[0], objectives[1]), (objectives[0], objectives[2]), (objectives[1], objectives[2]),
        (objectives[2], objectives[3]), (objectives[2], objectives[4]), (objectives[3], objectives[4]),
        (objectives[1], enemies[0]), (objectives[2], enemies[1]), (objectives[4], enemies[2]),
    ]
    add_roads(grid, connections, width, height)
    objective_specs = [
        ("command", "objective_command_post", 3),
        ("radar", "objective_radar", 3),
        ("crossing", "objective_central_crossing", 4),
        ("depot", "objective_supply_depot", 3),
        ("airfield", "objective_airfield", 3),
    ]
    return {
        "id": identifier,
        "version": 4,
        "nameKey": source["nameKey"],
        "biomes": ["weekly"],
        "width": width,
        "height": height,
        "gridLayout": "ODD_R_OFFSET",
        "baseTerrain": base,
        "cells": cells_for(grid, base),
        "playerEntries": [
            {"id": f"A{index + 1}", "nameKey": "entry_west_road", "position": position(value)}
            for index, value in enumerate(entries)
        ],
        "enemyEntries": [
            {"id": f"B{index + 1}", "nameKey": "entry_east_route", "position": position(value)}
            for index, value in enumerate(enemies)
        ],
        "objectives": [
            {"id": objective_id, "nameKey": name_key, "position": position(value), "captureSteps": capture_steps}
            for (objective_id, name_key, capture_steps), value in zip(objective_specs, objectives)
        ],
    }


def main() -> None:
    assignments = json.loads(INDEX_PATH.read_text())
    personal = [personal_definition(assignment) for assignment in assignments]
    normalized_assignments = [
        {
            "id": assignment["id"],
            "location": assignment["location"],
            "biome": assignment["biome"],
            "templateId": assignment["id"],
            "variant": "IDENTITY",
        }
        for assignment in assignments
    ]
    weekly_source = json.loads(WEEKLY_PATH.read_text())
    weekly = [weekly_definition(source) for source in weekly_source]
    PERSONAL_PATH.write_text(json.dumps(personal, ensure_ascii=False, indent=2) + "\n")
    INDEX_PATH.write_text(json.dumps(normalized_assignments, ensure_ascii=False, indent=2) + "\n")
    WEEKLY_PATH.write_text(json.dumps(weekly, ensure_ascii=False, indent=2) + "\n")
    print(f"Generated {len(personal)} personal and {len(weekly)} weekly offset-grid maps")


if __name__ == "__main__":
    main()
