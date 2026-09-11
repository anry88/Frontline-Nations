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
MAP_VERSION = 5


def seeded(identifier: str) -> random.Random:
    seed = int.from_bytes(hashlib.sha256(f"frontline-map-v{MAP_VERSION}:{identifier}".encode()).digest()[:8], "big")
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


def entry_name_key(value: tuple[int, int], width: int, height: int) -> str:
    q, r = value
    if r == 0:
        return "entry_north"
    if r == height - 1:
        return "entry_south_road"
    if q == 0:
        return "entry_west_road"
    if q == width - 1:
        return "entry_east_route"
    raise ValueError(f"Entry {value} is not on the {width}×{height} boundary")


def road_graph(
    entries: list[tuple[int, int]],
    enemies: list[tuple[int, int]],
    objectives: list[tuple[int, int]],
    rng: random.Random,
) -> list[tuple[tuple[int, int], tuple[int, int]]]:
    """Connect every deployment edge through an objective-specific road network."""
    connections: set[tuple[tuple[int, int], tuple[int, int]]] = set()

    def add(first: tuple[int, int], second: tuple[int, int]) -> None:
        connections.add(tuple(sorted((first, second))))

    def nearest(value: tuple[int, int]) -> tuple[int, int]:
        return min(objectives, key=lambda objective: (distance(value, objective), objective))

    for anchor in entries + enemies:
        add(anchor, nearest(anchor))

    # A minimum spanning tree keeps the objective network connected without
    # forcing every map through the same central hub.
    connected = {objectives[0]}
    pending = set(objectives[1:])
    while pending:
        first, second = min(
            ((first, second) for first in connected for second in pending),
            key=lambda pair: (distance(*pair), pair),
        )
        add(first, second)
        connected.add(second)
        pending.remove(second)

    # Larger maps gain two deterministic alternative links, personal sectors
    # one, producing loops and flanking routes rather than a single road tree.
    candidates = [
        (first, second)
        for index, first in enumerate(objectives)
        for second in objectives[index + 1:]
        if tuple(sorted((first, second))) not in connections
    ]
    rng.shuffle(candidates)
    for first, second in candidates[:2 if len(objectives) >= 5 else 1]:
        add(first, second)
    return sorted(connections)


# These are authored tactical anchors, not random offsets around one template.
# Keeping them explicit makes each named battlefield reviewable and stable while
# terrain fill and road routing remain deterministic.
PERSONAL_LAYOUTS: dict[str, dict[str, list[tuple[int, int]]]] = {
    "carpathian-pass": {"player": [(1, 11), (5, 11), (8, 9)], "enemy": [(0, 2), (4, 0), (7, 0)], "objectives": [(2, 8), (5, 5), (6, 2)]},
    "danube-valley": {"player": [(0, 2), (0, 6), (0, 10)], "enemy": [(8, 1), (8, 6), (8, 10)], "objectives": [(2, 3), (4, 7), (6, 9)]},
    "adriatic-coast": {"player": [(1, 0), (4, 0), (7, 0)], "enemy": [(0, 10), (4, 11), (8, 9)], "objectives": [(2, 3), (6, 5), (4, 9)]},
    "patagonian-plateau": {"player": [(0, 8), (2, 11), (6, 11)], "enemy": [(3, 0), (8, 3), (8, 7)], "objectives": [(2, 8), (4, 5), (7, 4)]},
    "sahara-corridor": {"player": [(0, 1), (3, 0), (0, 7)], "enemy": [(8, 4), (8, 10), (5, 11)], "objectives": [(2, 3), (4, 6), (6, 8)]},
    "altai-frontier": {"player": [(0, 9), (2, 11), (7, 11)], "enemy": [(1, 0), (6, 0), (8, 5)], "objectives": [(2, 7), (6, 5), (5, 2)]},
    "polesie-frontier": {"player": [(0, 1), (0, 5), (0, 10)], "enemy": [(8, 2), (8, 7), (8, 10)], "objectives": [(3, 2), (5, 6), (3, 9)]},
    "northern-tundra": {"player": [(1, 0), (5, 0), (8, 3)], "enemy": [(0, 7), (3, 11), (7, 11)], "objectives": [(3, 3), (6, 7), (2, 9)]},
    "gobi-basin": {"player": [(0, 2), (4, 0), (8, 1)], "enemy": [(0, 10), (4, 11), (8, 9)], "objectives": [(2, 4), (6, 5), (4, 8)]},
    "nile-delta": {"player": [(0, 3), (0, 8), (3, 11)], "enemy": [(5, 0), (8, 3), (8, 9)], "objectives": [(2, 5), (5, 3), (6, 8)]},
    "anatolian-plateau": {"player": [(1, 11), (4, 11), (8, 8)], "enemy": [(0, 3), (4, 0), (8, 2)], "objectives": [(2, 7), (4, 4), (7, 6)]},
    "rhine-plain": {"player": [(0, 2), (0, 9), (4, 11)], "enemy": [(4, 0), (8, 2), (8, 9)], "objectives": [(2, 6), (4, 3), (6, 7)]},
    "atlas-foothills": {"player": [(0, 4), (0, 10), (5, 11)], "enemy": [(2, 0), (8, 1), (8, 7)], "objectives": [(2, 8), (3, 4), (6, 3)]},
    "amazon-lowlands": {"player": [(0, 1), (4, 0), (8, 2)], "enemy": [(0, 9), (5, 11), (8, 8)], "objectives": [(2, 5), (4, 8), (6, 4)]},
    "rift-valley": {"player": [(1, 0), (6, 0), (8, 5)], "enemy": [(0, 6), (2, 11), (7, 11)], "objectives": [(2, 3), (6, 7), (3, 9)]},
    "mekong-delta": {"player": [(0, 3), (0, 7), (2, 11)], "enemy": [(6, 0), (8, 4), (8, 9)], "objectives": [(2, 6), (5, 3), (6, 9)]},
    "deccan-plateau": {"player": [(0, 5), (3, 11), (8, 10)], "enemy": [(0, 1), (5, 0), (8, 4)], "objectives": [(2, 3), (4, 8), (7, 6)]},
    "andean-pass": {"player": [(0, 10), (4, 11), (8, 8)], "enemy": [(0, 3), (3, 0), (8, 1)], "objectives": [(2, 7), (4, 5), (6, 3)]},
    "arabian-coast": {"player": [(0, 1), (0, 8), (4, 11)], "enemy": [(4, 0), (8, 3), (8, 10)], "objectives": [(2, 3), (3, 8), (6, 6)]},
    "great-plains": {"player": [(1, 11), (6, 11), (8, 7)], "enemy": [(0, 4), (2, 0), (7, 0)], "objectives": [(2, 8), (4, 3), (6, 6)]},
    "caucasus-ridge": {"player": [(0, 2), (3, 0), (8, 1)], "enemy": [(0, 8), (5, 11), (8, 10)], "objectives": [(2, 4), (4, 9), (7, 5)]},
    "baltic-marshes": {"player": [(0, 5), (0, 10), (6, 11)], "enemy": [(1, 0), (8, 2), (8, 7)], "objectives": [(2, 8), (4, 3), (7, 6)]},
    "australian-outback": {"player": [(0, 1), (5, 0), (8, 4)], "enemy": [(0, 9), (3, 11), (8, 10)], "objectives": [(2, 5), (5, 8), (6, 3)]},
    "kamchatka-coast": {"player": [(0, 4), (2, 11), (8, 9)], "enemy": [(0, 1), (6, 0), (8, 3)], "objectives": [(2, 7), (5, 3), (6, 7)]},
}


def personal_definition(assignment: dict[str, str]) -> dict[str, Any]:
    identifier, biome = assignment["id"], assignment["biome"]
    width, height = 9, 12
    base, grid = terrain_grid(identifier, biome, width, height)
    layout = PERSONAL_LAYOUTS[identifier]
    entries, enemies, objectives = layout["player"], layout["enemy"], layout["objectives"]
    connections = road_graph(entries, enemies, objectives, seeded(f"roads:{identifier}"))
    add_roads(grid, connections, width, height)
    return {
        "id": identifier,
        "version": MAP_VERSION,
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
            {"id": entry_id, "nameKey": entry_name_key(value, width, height), "position": position(value)}
            for entry_id, value in zip(("S", "W", "E"), entries)
        ],
        "enemyEntries": [
            {"id": entry_id, "nameKey": entry_name_key(value, width, height), "position": position(value)}
            for entry_id, value in zip(("N", "NW", "NE"), enemies)
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


WEEKLY_LAYOUTS: dict[str, dict[str, list[tuple[int, int]]]] = {
    # Wide river front: opposing banks, offset crossings and a southern hook.
    "grand-river-crossing": {
        "player": [(0, 3), (0, 11), (5, 20)],
        "enemy": [(9, 0), (14, 7), (14, 17)],
        "objectives": [(3, 4), (8, 3), (6, 9), (10, 14), (5, 17)],
    },
    # Vertical mountain advance through two passes rather than a lateral lane.
    "highland-corridor": {
        "player": [(1, 20), (7, 20), (14, 16)],
        "enemy": [(0, 4), (5, 0), (12, 0)],
        "objectives": [(3, 15), (6, 11), (11, 16), (9, 6), (3, 5)],
    },
    # Diagonal desert pincer with objectives spread along three approach axes.
    "desert-trident": {
        "player": [(0, 1), (4, 0), (0, 13)],
        "enemy": [(14, 7), (10, 20), (14, 19)],
        "objectives": [(3, 4), (8, 6), (5, 11), (11, 13), (8, 17)],
    },
    # Northern and southern forces converge on an irregular forest basin.
    "forest-basin": {
        "player": [(1, 0), (7, 0), (13, 0)],
        "enemy": [(0, 17), (6, 20), (13, 20)],
        "objectives": [(4, 5), (10, 4), (7, 9), (3, 14), (10, 16)],
    },
    # Landings arrive from two coastal edges against an inland defence arc.
    "coastal-breach": {
        "player": [(0, 4), (0, 15), (6, 20)],
        "enemy": [(5, 0), (14, 5), (14, 13)],
        "objectives": [(3, 8), (7, 4), (6, 13), (11, 9), (10, 17)],
    },
    # A corner-to-corner tundra battle with a deliberately off-centre hinge.
    "tundra-line": {
        "player": [(0, 2), (2, 0), (0, 18)],
        "enemy": [(14, 2), (12, 20), (14, 18)],
        "objectives": [(4, 4), (10, 5), (5, 10), (9, 14), (5, 17)],
    },
    # Mixed north/west versus south/east deployment around marsh causeways.
    "marsh-causeway": {
        "player": [(0, 6), (3, 0), (10, 0)],
        "enemy": [(4, 20), (14, 10), (12, 20)],
        "objectives": [(3, 8), (7, 4), (8, 10), (5, 15), (11, 14)],
    },
    # Opposing sweeping arcs create several viable encirclement routes.
    "steppe-encirclement": {
        "player": [(0, 4), (0, 16), (8, 20)],
        "enemy": [(6, 0), (14, 4), (14, 16)],
        "objectives": [(3, 6), (8, 3), (6, 11), (11, 10), (9, 17)],
    },
    # Long canyon diagonal with side entrances that reward flanking mobility.
    "canyon-network": {
        "player": [(1, 20), (0, 13), (5, 20)],
        "enemy": [(9, 0), (14, 7), (13, 0)],
        "objectives": [(3, 16), (5, 10), (9, 14), (8, 6), (12, 4)],
    },
    # Cross-front industrial assault, distinct from every natural terrain map.
    "industrial-front": {
        "player": [(0, 2), (0, 10), (4, 20)],
        "enemy": [(10, 0), (14, 10), (14, 19)],
        "objectives": [(3, 4), (8, 5), (5, 11), (11, 12), (8, 17)],
    },
}


def weekly_definition(source: dict[str, Any]) -> dict[str, Any]:
    identifier = source["id"]
    width, height = 15, 21
    base, grid = terrain_grid(identifier, WEEKLY_THEMES[identifier], width, height)
    layout = WEEKLY_LAYOUTS[identifier]
    entries, enemies, objectives = layout["player"], layout["enemy"], layout["objectives"]
    connections = road_graph(entries, enemies, objectives, seeded(f"roads:{identifier}"))
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
        "version": MAP_VERSION,
        "nameKey": source["nameKey"],
        "biomes": ["weekly"],
        "width": width,
        "height": height,
        "gridLayout": "ODD_R_OFFSET",
        "baseTerrain": base,
        "cells": cells_for(grid, base),
        "playerEntries": [
            {"id": f"A{index + 1}", "nameKey": entry_name_key(value, width, height), "position": position(value)}
            for index, value in enumerate(entries)
        ],
        "enemyEntries": [
            {"id": f"B{index + 1}", "nameKey": entry_name_key(value, width, height), "position": position(value)}
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
