package com.tggames.frontline.campaign

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeeklyBattleMapCatalogTest {
    private val catalog = WeeklyBattleMapCatalog(jacksonObjectMapper())

    @Test
    fun `catalog contains ten large maps with five capture points`() {
        assertThat(catalog.maps).hasSize(10)
        assertThat(catalog.maps).allSatisfy {
            assertThat(it.width * it.height).isGreaterThanOrEqualTo(99)
            assertThat(it.objectives).hasSize(5)
        }
    }
}
