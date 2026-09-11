package com.tggames.frontline.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquipmentCatalogTest {
    private val catalog = EquipmentCatalog(ObjectMapper().registerKotlinModule())

    @Test
    fun `catalog exposes all initial classes with every supported language`() {
        assertThat(catalog.units).hasSize(7)
        assertThat(catalog.units.map { it.code }).contains("MBT", "ARTILLERY", "ATTACK_AIRCRAFT", "AIR_DEFENSE", "RECON_VEHICLE")
        assertThat(catalog.units).allSatisfy { definition ->
            assertThat(GameLanguage.entries.map(definition::name)).allSatisfy { assertThat(it).isNotBlank() }
            assertThat(definition.spatial.movementPoints).isBetween(1, 8)
            assertThat(definition.spatial.minimumRange).isBetween(1, definition.spatial.weaponRange)
            assertThat(definition.spatial.sightRange).isBetween(1, 6)
        }
    }

    @Test
    fun `level scaling is integer deterministic and capped`() {
        val base = catalog.require("MBT").stats
        assertThat(base.scaled(1)).isEqualTo(base)
        assertThat(base.scaled(5).attack).isEqualTo(base.attack * 148 / 100)
        assertThat(base.scaled(99)).isEqualTo(base.scaled(5))
    }
}
