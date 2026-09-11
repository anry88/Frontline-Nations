package com.tggames.frontline.campaign

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
            assertThat(it.objectives).hasSize(5)
            val image = ImageIO.read(ClassPathResource("static/assets/maps/weekly/${it.id}.png").inputStream)
            assertThat(image.width).isEqualTo(image.height)
        }
    }

    @Test
    fun `generated objective blocks exist`() {
        listOf("communications", "crossing", "depot", "command", "radar", "airfield").forEach {
            assertThat(ClassPathResource("static/assets/maps/tiles/objective-$it.png").exists()).isTrue()
        }
    }
}
