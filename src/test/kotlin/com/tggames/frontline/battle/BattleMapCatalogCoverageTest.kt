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
            assertThat(it.width).isEqualTo(9)
            assertThat(it.height).isEqualTo(12)
            assertThat(it.objectives).hasSizeGreaterThanOrEqualTo(2)
            assertThat(it.hasNaturalTerrainTransitions()).isTrue()
            assertThat(it.hasSuitableObjectiveSites()).isTrue()
            val image = ImageIO.read(ClassPathResource("static/assets/maps/personal/${it.id}.png").inputStream)
            assertThat(image.width).isEqualTo(image.height)
        }
    }
}
