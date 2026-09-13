package com.tggames.frontline.campaign

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.GridLayout
import com.tggames.frontline.battle.HexCoord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import javax.imageio.ImageIO

class WeeklyBattleMapCatalogTest {
    private val catalog = WeeklyBattleMapCatalog(jacksonObjectMapper())

    @Test
    fun `catalog contains ten large maps with five capture points`() {
        assertThat(catalog.maps).hasSize(10)
        assertThat(catalog.maps).allSatisfy {
            assertThat(it.version).isEqualTo(5)
            assertThat(it.width).isEqualTo(15)
            assertThat(it.height).isEqualTo(21)
            assertThat(it.gridLayout).isEqualTo(GridLayout.ODD_R_OFFSET)
            assertThat(it.objectives).hasSize(5)
            assertThat((it.cells.map { cell -> cell.terrain } + it.baseTerrain).distinct()).hasSizeGreaterThanOrEqualTo(4)
            assertThat(it.playerEntries + it.enemyEntries).allSatisfy { entry ->
                assertThat(entry.position.q in setOf(0, it.width - 1) || entry.position.r in setOf(0, it.height - 1)).isTrue()
                assertThat(entry.nameKey).isEqualTo(edgeNameKey(it, entry.position))
            }
            assertThat((it.playerEntries + it.enemyEntries).map { entry -> entry.position }).doesNotHaveDuplicates()
            val image = ImageIO.read(ClassPathResource("static/assets/maps/weekly/${it.id}.png").inputStream)
            assertThat(image.width).isEqualTo(1_536)
            assertThat(image.height).isEqualTo(1_536)
        }
    }

    @Test
    fun `weekly maps vary front orientation and all strategic anchors`() {
        val anchorSignatures = catalog.maps.map { map ->
            (map.playerEntries + map.enemyEntries).map { it.position } + map.objectives.map { it.position }
        }
        val objectiveSignatures = catalog.maps.map { map -> map.objectives.map { it.position } }
        val playerEdgePatterns = catalog.maps.map { map -> map.playerEntries.map { edgeOf(map, it.position) } }

        assertThat(anchorSignatures).doesNotHaveDuplicates()
        assertThat(objectiveSignatures).doesNotHaveDuplicates()
        assertThat(playerEdgePatterns.distinct()).hasSizeGreaterThanOrEqualTo(6)
    }

    @Test
    fun `generated objective blocks exist`() {
        listOf("communications", "crossing", "depot", "command", "radar", "airfield").forEach {
            assertThat(ClassPathResource("static/assets/maps/tiles/objective-$it.png").exists()).isTrue()
        }
    }

    @Test
    fun `front deployment markers match the labels rendered on weekly maps`() {
        val map = catalog.maps.first()
        val sideA = FrontDeployment(
            map.playerEntries,
            map.objectives,
            map.playerEntries.mapIndexed { index, entry -> entry.id to ('A' + index).toString() }.toMap(),
        )
        val sideB = FrontDeployment(
            map.enemyEntries,
            map.objectives,
            map.enemyEntries.mapIndexed { index, entry -> entry.id to ('X' + index).toString() }.toMap(),
        )

        assertThat(sideA.entries.map { sideA.entryMarker(it.id) }).containsExactly("A", "B", "C")
        assertThat(sideB.entries.map { sideB.entryMarker(it.id) }).containsExactly("X", "Y", "Z")
        assertThat(sideA.objectives.map { sideA.objectiveMarker(it.id) }).containsExactly("1", "2", "3", "4", "5")
    }

    private fun edgeOf(map: BattleMapDefinition, position: HexCoord): String = when {
        position.r == 0 -> "N"
        position.r == map.height - 1 -> "S"
        position.q == 0 -> "W"
        position.q == map.width - 1 -> "E"
        else -> error("$position is not on the edge of ${map.id}")
    }

    private fun edgeNameKey(map: BattleMapDefinition, position: HexCoord): String = when (edgeOf(map, position)) {
        "N" -> "entry_north"
        "S" -> "entry_south_road"
        "W" -> "entry_west_road"
        else -> "entry_east_route"
    }
}
