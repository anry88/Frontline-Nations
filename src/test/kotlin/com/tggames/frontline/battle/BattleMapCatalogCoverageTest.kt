package com.tggames.frontline.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import javax.imageio.ImageIO

class BattleMapCatalogCoverageTest {
    @Test
    fun `all personal battlefields have distinct spatial maps`() {
        val maps = BattleMapCatalog(jacksonObjectMapper()).maps
        assertThat(maps).hasSize(24)
        assertThat(maps.map { it.id }).doesNotHaveDuplicates()
        assertThat(maps).allSatisfy {
            assertThat(it.version).isEqualTo(5)
            assertThat(it.width).isEqualTo(9)
            assertThat(it.height).isEqualTo(12)
            assertThat(it.gridLayout).isEqualTo(GridLayout.ODD_R_OFFSET)
            assertThat(it.objectives).hasSizeGreaterThanOrEqualTo(2)
            assertThat((it.cells.map { cell -> cell.terrain } + it.baseTerrain).distinct()).hasSizeGreaterThanOrEqualTo(4)
            assertThat(it.playerEntries + it.enemyEntries).allSatisfy { entry ->
                assertThat(entry.position.q in setOf(0, it.width - 1) || entry.position.r in setOf(0, it.height - 1)).isTrue()
                assertThat(entry.nameKey).isEqualTo(edgeNameKey(it, entry.position))
            }
            assertThat((it.playerEntries + it.enemyEntries).map { entry -> entry.position }).doesNotHaveDuplicates()
            assertThat(it.hasNaturalTerrainTransitions()).isTrue()
            assertThat(it.hasSuitableObjectiveSites()).isTrue()
            val image = ImageIO.read(ClassPathResource("static/assets/maps/personal/${it.id}.png").inputStream)
            assertThat(image.width).isEqualTo(1_536)
            assertThat(image.height).isEqualTo(1_536)
        }
    }

    @Test
    fun `personal maps use genuinely different deployment and objective layouts`() {
        val maps = BattleMapCatalog(jacksonObjectMapper()).maps
        val anchorSignatures = maps.map { map ->
            (map.playerEntries + map.enemyEntries).map { it.position } + map.objectives.map { it.position }
        }
        val objectiveSignatures = maps.map { map -> map.objectives.map { it.position } }
        val playerEdgePatterns = maps.map { map -> map.playerEntries.map { edgeOf(map, it.position) } }

        assertThat(anchorSignatures).doesNotHaveDuplicates()
        assertThat(objectiveSignatures).doesNotHaveDuplicates()
        assertThat(playerEdgePatterns.distinct()).hasSizeGreaterThanOrEqualTo(8)
    }

    @Test
    fun `offset grid keeps rectangular rows while preserving six-way adjacency`() {
        val map = BattleMapCatalog(jacksonObjectMapper()).maps.first()
        assertThat(map.neighbors(HexCoord(4, 4))).containsExactlyInAnyOrder(
            HexCoord(5, 4), HexCoord(4, 3), HexCoord(3, 3),
            HexCoord(3, 4), HexCoord(3, 5), HexCoord(4, 5),
        )
        assertThat(map.neighbors(HexCoord(4, 5))).containsExactlyInAnyOrder(
            HexCoord(5, 5), HexCoord(5, 4), HexCoord(4, 4),
            HexCoord(3, 5), HexCoord(4, 6), HexCoord(5, 6),
        )
        val line = map.lineBetween(HexCoord(0, 0), HexCoord(8, 11))
        assertThat(line).allMatch(map::contains)
        assertThat(line).hasSize(map.distanceBetween(line.first(), line.last()) + 1)
        line.zipWithNext().forEach { (first, second) ->
            assertThat(second).isIn(map.neighbors(first))
            assertThat(first).isIn(map.neighbors(second))
        }
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
