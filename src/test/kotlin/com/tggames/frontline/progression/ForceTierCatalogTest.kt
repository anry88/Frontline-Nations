package com.tggames.frontline.progression

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient

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
    fun `capacity expansions follow level-gated tier ceilings`() {
        assertThat(catalog.nextExpansion(10)?.maxCp).isEqualTo(25)
        assertThat(catalog.nextExpansion(25)?.maxCp).isEqualTo(50)
        assertThat(catalog.nextExpansion(500)?.unlockLevel).isEqualTo(50)
        assertThat(catalog.nextExpansion(1_000)).isNull()
    }

    @Test
    fun `capacity expansion consumes research only after its commander level gate`() {
        val service = CommandProgressionService(mock(JdbcClient::class.java), catalog)

        val levelLocked = service.progression(level = 9, research = 10_000, capacity = 25)
        val researchLocked = service.progression(level = 10, research = 149, capacity = 25)
        val available = service.progression(level = 10, research = 150, capacity = 25)
        val maximum = service.progression(level = 50, research = 10_000, capacity = 1_000)

        assertThat(service.expansionBlock(levelLocked)).isEqualTo(CapacityExpansionStatus.LEVEL_LOCKED)
        assertThat(service.expansionBlock(researchLocked)).isEqualTo(CapacityExpansionStatus.INSUFFICIENT_RESEARCH)
        assertThat(service.expansionBlock(available)).isNull()
        assertThat(service.expansionBlock(maximum)).isEqualTo(CapacityExpansionStatus.MAXIMUM_REACHED)
    }
}
