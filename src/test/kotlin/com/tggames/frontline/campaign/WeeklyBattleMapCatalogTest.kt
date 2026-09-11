package com.tggames.frontline.campaign

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.battle.GridLayout
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
            assertThat(it.width).isEqualTo(15)
            assertThat(it.height).isEqualTo(21)
            assertThat(it.gridLayout).isEqualTo(GridLayout.ODD_R_OFFSET)
            assertThat(it.objectives).hasSize(5)
            assertThat((it.cells.map { cell -> cell.terrain } + it.baseTerrain).distinct()).hasSizeGreaterThanOrEqualTo(4)
            assertThat(it.playerEntries + it.enemyEntries).allSatisfy { entry ->
                assertThat(entry.position.q in setOf(0, it.width - 1) || entry.position.r in setOf(0, it.height - 1)).isTrue()
            }
            assertThat((it.playerEntries + it.enemyEntries).map { entry -> entry.position }).doesNotHaveDuplicates()
            val image = ImageIO.read(ClassPathResource("static/assets/maps/weekly/${it.id}.png").inputStream)
            assertThat(image.width).isEqualTo(1_536)
            assertThat(image.height).isEqualTo(1_536)
        }
    }

    @Test
    fun `generated objective blocks exist`() {
        listOf("communications", "crossing", "depot", "command", "radar", "airfield").forEach {
            assertThat(ClassPathResource("static/assets/maps/tiles/objective-$it.png").exists()).isTrue()
        }
    }
}
