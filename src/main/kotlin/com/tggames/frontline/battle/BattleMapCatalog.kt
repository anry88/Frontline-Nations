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

enum class MapVariant { IDENTITY, ROTATE_180, TRANSPOSE, TRANSPOSE_ROTATE_180 }

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
        require(template.width == template.height || assignment.variant in setOf(MapVariant.IDENTITY, MapVariant.ROTATE_180)) {
            "Transpose variants require a square template"
        }
        fun transform(position: HexCoord): HexCoord = when (assignment.variant) {
            MapVariant.IDENTITY -> position
            MapVariant.ROTATE_180 -> HexCoord(template.width - 1 - position.q, template.height - 1 - position.r)
            MapVariant.TRANSPOSE -> HexCoord(position.r, position.q)
            MapVariant.TRANSPOSE_ROTATE_180 -> HexCoord(template.width - 1 - position.r, template.height - 1 - position.q)
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
        return template.resolvedFor(assignment.biome).copy(
            id = assignment.id,
            version = 2,
            cells = template.cells.map { it.copy(q = transform(it.position).q, r = transform(it.position).r) },
            playerEntries = template.playerEntries.map(::entry),
            enemyEntries = template.enemyEntries.map(::entry),
            objectives = template.objectives.map { it.copy(position = transform(it.position)) },
            biomeBaseTerrains = emptyMap(),
        )
    }

    private fun validate(definitions: List<BattleMapDefinition>) {
        require(definitions.isNotEmpty()) { "Battle map catalog cannot be empty" }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Battle map ids must be unique" }
        definitions.forEach { map ->
            require(map.version > 0 && map.width in 5..20 && map.height in 5..20)
            require(map.biomes.isNotEmpty()) { "${map.id} must cover at least one biome" }
            require(map.cells.map { it.position }.distinct().size == map.cells.size) { "${map.id} has duplicate cells" }
            require(map.playerEntries.size >= 3 && map.enemyEntries.isNotEmpty()) { "${map.id} needs deployment entries" }
            require(map.objectives.size >= 2) { "${map.id} needs multiple objectives" }
            require(map.objectives.map { it.position }.distinct().size == map.objectives.size) { "${map.id} has duplicate objectives" }
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
