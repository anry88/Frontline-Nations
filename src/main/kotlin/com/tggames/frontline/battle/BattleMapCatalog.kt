package com.tggames.frontline.battle

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.catalog.MovementProfile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import kotlin.math.abs
import kotlin.math.max

enum class TerrainType(
    val symbol: String,
    val cover: Int,
    val blocksLineOfSight: Boolean,
) {
    PLAIN("·", 0, false),
    ROAD("═", 0, false),
    FOREST("♣", 2, true),
    HILL("▲", 1, true),
    MOUNTAIN("△", 3, true),
    WATER("≈", 0, false),
    SWAMP("≋", 1, false),
    DESERT("∘", 0, false),
    TUNDRA("∗", 1, false),
    COAST("∼", 0, false),
    ;

    fun movementCost(profile: MovementProfile): Int? {
        if (profile == MovementProfile.AIR) return 1
        return when (this) {
            ROAD -> 1
            PLAIN, DESERT, COAST -> 2
            HILL, FOREST, TUNDRA -> 3
            SWAMP -> if (profile == MovementProfile.TRACKED) 3 else 5
            MOUNTAIN, WATER -> null
        }
    }
}

data class HexCoord(val q: Int, val r: Int) {
    fun distanceTo(other: HexCoord): Int {
        val ds = (-q - r) - (-other.q - other.r)
        return max(max(abs(q - other.q), abs(r - other.r)), abs(ds))
    }
}

data class MapCellOverride(
    val q: Int,
    val r: Int,
    val terrain: TerrainType,
    val elevation: Int = 0,
) {
    val position: HexCoord get() = HexCoord(q, r)
}

data class DeploymentEntry(
    val id: String,
    val nameKey: String,
    val position: HexCoord,
)

data class StrategicObjective(
    val id: String,
    val nameKey: String,
    val position: HexCoord,
    val captureSteps: Int,
)

data class BattleMapDefinition(
    val id: String,
    val version: Int,
    val nameKey: String,
    val biomes: List<String>,
    val width: Int,
    val height: Int,
    val baseTerrain: TerrainType,
    val biomeBaseTerrains: Map<String, TerrainType> = emptyMap(),
    val cells: List<MapCellOverride>,
    val playerEntries: List<DeploymentEntry>,
    val enemyEntries: List<DeploymentEntry>,
    val objectives: List<StrategicObjective>,
) {
    private val overrides: Map<HexCoord, MapCellOverride> get() = cells.associateBy { it.position }

    fun contains(position: HexCoord): Boolean = position.q in 0 until width && position.r in 0 until height

    fun terrainAt(position: HexCoord): TerrainType = overrides[position]?.terrain ?: baseTerrain

    fun elevationAt(position: HexCoord): Int = overrides[position]?.elevation ?: 0

    fun neighbors(position: HexCoord): List<HexCoord> = DIRECTIONS
        .map { HexCoord(position.q + it.q, position.r + it.r) }
        .filter(::contains)

    fun resolvedFor(biome: String): BattleMapDefinition = copy(
        biomes = listOf(biome),
        baseTerrain = biomeBaseTerrains[biome] ?: baseTerrain,
    )

    companion object {
        private val DIRECTIONS = listOf(
            HexCoord(1, 0), HexCoord(1, -1), HexCoord(0, -1),
            HexCoord(-1, 0), HexCoord(-1, 1), HexCoord(0, 1),
        )
    }
}

fun BattleMapDefinition.withObjectiveSites(): BattleMapDefinition {
    val prepared = cells.associateBy { it.position }.toMutableMap()
    val flatTerrain = when (baseTerrain) {
        TerrainType.PLAIN, TerrainType.DESERT, TerrainType.TUNDRA, TerrainType.COAST -> baseTerrain
        else -> TerrainType.PLAIN
    }
    objectives.forEach { objective ->
        val current = terrainAt(objective.position)
        val required = when (objective.nameKey) {
            "objective_signal_tower", "objective_radar" -> TerrainType.HILL
            "objective_central_crossing", "objective_north_crossing", "objective_south_crossing" -> TerrainType.ROAD
            "objective_airfield" -> flatTerrain
            "objective_supply_depot", "objective_command_post" -> if (current == TerrainType.ROAD) current else flatTerrain
            else -> current
        }
        prepared[objective.position] = MapCellOverride(objective.position.q, objective.position.r, required)
    }
    return copy(cells = prepared.values.sortedWith(compareBy<MapCellOverride> { it.r }.thenBy { it.q }))
}

fun BattleMapDefinition.hasNaturalTerrainTransitions(): Boolean {
    val forbidden = setOf(
        setOf(TerrainType.DESERT, TerrainType.TUNDRA),
        setOf(TerrainType.DESERT, TerrainType.COAST),
        setOf(TerrainType.TUNDRA, TerrainType.COAST),
    )
    for (r in 0 until height) {
        for (q in 0 until width) {
            val position = HexCoord(q, r)
            val terrain = terrainAt(position)
            val neighbors = neighbors(position).map(::terrainAt)
            if (terrain == TerrainType.COAST && (TerrainType.WATER !in neighbors || neighbors.none { it !in setOf(TerrainType.WATER, TerrainType.COAST) })) return false
            if (neighbors.any { setOf(terrain, it) in forbidden }) return false
        }
    }
    return true
}

fun BattleMapDefinition.hasSuitableObjectiveSites(): Boolean = objectives.all { objective ->
    when (objective.nameKey) {
        "objective_signal_tower", "objective_radar" -> terrainAt(objective.position) == TerrainType.HILL
        "objective_central_crossing", "objective_north_crossing", "objective_south_crossing" -> terrainAt(objective.position) == TerrainType.ROAD
        "objective_airfield" -> terrainAt(objective.position) in setOf(TerrainType.PLAIN, TerrainType.DESERT, TerrainType.TUNDRA, TerrainType.COAST)
        "objective_supply_depot", "objective_command_post" -> terrainAt(objective.position) in setOf(TerrainType.ROAD, TerrainType.PLAIN, TerrainType.DESERT, TerrainType.TUNDRA, TerrainType.COAST)
        else -> true
    }
}

enum class MapVariant { IDENTITY, ROTATE_180, MIRROR_HORIZONTAL, MIRROR_VERTICAL }

data class BattlefieldMapAssignment(
    val id: String,
    val location: String,
    val biome: String,
    val templateId: String,
    val variant: MapVariant,
)

@Component
class BattleMapCatalog(objectMapper: ObjectMapper) {
    private val templates: List<BattleMapDefinition> = ClassPathResource("catalog/battle-maps.json").inputStream.use {
        objectMapper.readValue(it, object : TypeReference<List<BattleMapDefinition>>() {})
    }
    private val assignments: List<BattlefieldMapAssignment> = ClassPathResource("catalog/battlefield-map-index.json").inputStream.use {
        objectMapper.readValue(it, object : TypeReference<List<BattlefieldMapAssignment>>() {})
    }
    val maps: List<BattleMapDefinition> = assignments.map(::expand).also(::validate)

    fun forBattlefield(location: String, biome: String): BattleMapDefinition =
        maps.firstOrNull { it.biomes.single() == biome && assignments.first { assignment -> assignment.id == it.id }.location == location }
            ?: maps.firstOrNull { assignments.first { assignment -> assignment.id == it.id }.location == location }
            ?: forBiome(biome)

    fun forBiome(biome: String): BattleMapDefinition =
        (maps.firstOrNull { biome in it.biomes } ?: maps.first()).resolvedFor(biome)

    private fun expand(assignment: BattlefieldMapAssignment): BattleMapDefinition {
        val template = requireNotNull(templates.firstOrNull { it.id == assignment.templateId }) {
            "Unknown map template ${assignment.templateId}"
        }
        fun transform(position: HexCoord): HexCoord = when (assignment.variant) {
            MapVariant.IDENTITY -> position
            MapVariant.ROTATE_180 -> HexCoord(template.width - 1 - position.q, template.height - 1 - position.r)
            MapVariant.MIRROR_HORIZONTAL -> HexCoord(template.width - 1 - position.q, position.r)
            MapVariant.MIRROR_VERTICAL -> HexCoord(position.q, template.height - 1 - position.r)
        }
        fun entry(entry: DeploymentEntry): DeploymentEntry {
            val position = transform(entry.position)
            val nameKey = when {
                position.r == 0 -> "entry_north"
                position.r == template.height - 1 -> "entry_south_road"
                position.q == 0 -> "entry_west_road"
                position.q == template.width - 1 -> "entry_east_route"
                else -> entry.nameKey
            }
            return entry.copy(nameKey = nameKey, position = position)
        }
        val resolved = template.resolvedFor(assignment.biome)
        val transformedCells = template.cells.map { it.copy(q = transform(it.position).q, r = transform(it.position).r) }
        val naturalCells = when {
            assignment.biome == "побережье" -> coastalBanks(template, transformedCells)
            resolved.baseTerrain == TerrainType.DESERT -> transformedCells.filterNot { it.terrain == TerrainType.FOREST }
            else -> transformedCells
        }
        return resolved.copy(
            id = assignment.id,
            version = 3,
            baseTerrain = if (assignment.biome == "побережье") TerrainType.PLAIN else resolved.baseTerrain,
            cells = naturalCells,
            playerEntries = template.playerEntries.map(::entry),
            enemyEntries = template.enemyEntries.map(::entry),
            objectives = template.objectives.map { it.copy(position = transform(it.position)) },
            biomeBaseTerrains = emptyMap(),
        ).withObjectiveSites()
    }

    private fun coastalBanks(template: BattleMapDefinition, cells: List<MapCellOverride>): List<MapCellOverride> {
        val occupied = cells.associateBy { it.position }
        val bankCells = cells.asSequence()
            .filter { it.terrain == TerrainType.WATER }
            .flatMap { water -> template.neighbors(water.position).asSequence() }
            .filterNot(occupied::containsKey)
            .distinct()
            .map { MapCellOverride(it.q, it.r, TerrainType.COAST) }
            .toList()
        return cells + bankCells
    }

    private fun validate(definitions: List<BattleMapDefinition>) {
        require(definitions.isNotEmpty()) { "Battle map catalog cannot be empty" }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Battle map ids must be unique" }
        definitions.forEach { map ->
            require(map.version > 0 && map.width == 9 && map.height == 12) { "${map.id} must be 9×12" }
            require(map.biomes.isNotEmpty()) { "${map.id} must cover at least one biome" }
            require(map.cells.map { it.position }.distinct().size == map.cells.size) { "${map.id} has duplicate cells" }
            require(map.playerEntries.size >= 3 && map.enemyEntries.isNotEmpty()) { "${map.id} needs deployment entries" }
            require(map.objectives.size >= 2) { "${map.id} needs multiple objectives" }
            require(map.objectives.map { it.position }.distinct().size == map.objectives.size) { "${map.id} has duplicate objectives" }
            require(map.hasNaturalTerrainTransitions()) { "${map.id} has an unnatural terrain transition" }
            require(map.hasSuitableObjectiveSites()) { "${map.id} has an objective on unsuitable terrain" }
            (map.cells.map { it.position } + map.playerEntries.map { it.position } + map.enemyEntries.map { it.position } + map.objectives.map { it.position })
                .forEach { require(map.contains(it)) { "${map.id} contains an out-of-bounds coordinate: $it" } }
            (map.playerEntries + map.enemyEntries).forEach {
                require(map.terrainAt(it.position).movementCost(MovementProfile.TRACKED) != null) { "${map.id} entry ${it.id} is blocked" }
            }
            map.objectives.forEach {
                require(it.captureSteps in 2..5) { "${map.id} objective ${it.id} has invalid capture time" }
                require(map.terrainAt(it.position).movementCost(MovementProfile.TRACKED) != null) { "${map.id} objective ${it.id} is blocked" }
            }
            map.playerEntries.forEach { entry ->
                val reachable = reachableGroundCells(map, entry.position)
                map.objectives.forEach { objective ->
                    require(objective.position in reachable) { "${map.id}: ${objective.id} is unreachable from ${entry.id}" }
                }
            }
        }
        require(definitions.size == assignments.size && assignments.size == 24) { "Every personal battlefield needs a spatial map" }
        require(assignments.map { it.location }.distinct().size == assignments.size) { "Battlefield locations must be unique" }
    }

    private fun reachableGroundCells(map: BattleMapDefinition, start: HexCoord): Set<HexCoord> {
        val visited = linkedSetOf(start)
        val pending = ArrayDeque<HexCoord>().apply { add(start) }
        while (pending.isNotEmpty()) {
            map.neighbors(pending.removeFirst()).forEach { next ->
                if (next !in visited && map.terrainAt(next).movementCost(MovementProfile.TRACKED) != null) {
                    visited += next
                    pending += next
                }
            }
        }
        return visited
    }
}
