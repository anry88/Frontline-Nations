package com.tggames.frontline.campaign

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.HexCoord
import com.tggames.frontline.battle.hasNaturalTerrainTransitions
import com.tggames.frontline.battle.hasSuitableObjectiveSites
import com.tggames.frontline.battle.withObjectiveSites
import com.tggames.frontline.catalog.MovementProfile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class WeeklyBattleMapCatalog(objectMapper: ObjectMapper) {
    val maps: List<BattleMapDefinition> = ClassPathResource("catalog/weekly-battle-maps.json").inputStream.use {
        objectMapper.readValue(it, object : TypeReference<List<BattleMapDefinition>>() {})
    }.map(BattleMapDefinition::withObjectiveSites).also(::validate)

    fun require(id: String): BattleMapDefinition = requireNotNull(maps.firstOrNull { it.id == id }) {
        "Unknown weekly battle map: $id"
    }

    fun find(id: String): BattleMapDefinition? = maps.firstOrNull { it.id == id }

    private fun validate(definitions: List<BattleMapDefinition>) {
        require(definitions.size == 10) { "Exactly ten weekly battle maps are required" }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Weekly map ids must be unique" }
        definitions.forEach { map ->
            require(map.version > 0 && map.width == 15 && map.height == 21) { "${map.id} must be 15×21" }
            require(map.objectives.size == 5) { "${map.id} must contain five objectives" }
            require(map.objectives.map { it.position }.distinct().size == 5) { "${map.id} has duplicate objectives" }
            require(map.hasNaturalTerrainTransitions()) { "${map.id} has an unnatural terrain transition" }
            require(map.hasSuitableObjectiveSites()) { "${map.id} has an objective on unsuitable terrain" }
            require(map.playerEntries.size >= 3 && map.enemyEntries.size >= 3) { "${map.id} needs three entries per side" }
            (map.cells.map { it.position } + map.playerEntries.map { it.position } + map.enemyEntries.map { it.position } + map.objectives.map { it.position })
                .forEach { require(map.contains(it)) { "${map.id} contains an out-of-bounds coordinate: $it" } }
            (map.playerEntries + map.enemyEntries).forEach {
                require(map.terrainAt(it.position).movementCost(MovementProfile.TRACKED) != null) { "${map.id} has a blocked entry" }
            }
            map.objectives.forEach {
                require(it.captureSteps in 2..6) { "${map.id} has an invalid capture duration" }
                require(map.terrainAt(it.position).movementCost(MovementProfile.TRACKED) != null) { "${map.id} has a blocked objective" }
            }
            (map.playerEntries + map.enemyEntries).forEach { entry ->
                val reachable = reachableGroundCells(map, entry.position)
                map.objectives.forEach { objective ->
                    require(objective.position in reachable) { "${map.id}: ${objective.id} is unreachable from ${entry.id}" }
                }
            }
        }
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
