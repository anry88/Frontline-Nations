package com.tggames.frontline.progression

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ForceTierCatalogTest {
    private val catalog = ForceTierCatalog(jacksonObjectMapper())

    @Test
    fun `battle categories are contiguous from ten to one thousand command points`() {
        assertThat(catalog.tiers.map { it.minCp to it.maxCp }).containsExactly(
            10 to 25,
            26 to 50,
            51 to 100,
            101 to 250,
            251 to 500,
            501 to 1_000,
        )
        assertThat(catalog.forDeployedCp(25).id).isEqualTo("detachment")
        assertThat(catalog.forDeployedCp(26).id).isEqualTo("company")
        assertThat(catalog.forDeployedCp(1_000).id).isEqualTo("corps")
    }

    @Test
    fun `each commander level adds one command point up to the supported maximum`() {
        assertThat(ForceTierCatalog.capacityForLevel(1)).isEqualTo(10)
        assertThat(ForceTierCatalog.capacityForLevel(2)).isEqualTo(11)
        assertThat(ForceTierCatalog.capacityForLevel(42)).isEqualTo(51)
        assertThat(ForceTierCatalog.capacityForLevel(991)).isEqualTo(1_000)
        assertThat(ForceTierCatalog.capacityForLevel(1_500)).isEqualTo(1_000)
    }
}
