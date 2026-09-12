package com.tggames.frontline.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.game.DailyRewardPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EquipmentCatalogTest {
    private val catalog = EquipmentCatalog(ObjectMapper().registerKotlinModule())

    @Test
    fun `catalog exposes all initial classes with every supported language`() {
        assertThat(catalog.units).hasSize(7)
        assertThat(catalog.units.map { it.code }).contains("MBT", "ARTILLERY", "ATTACK_AIRCRAFT", "AIR_DEFENSE", "RECON_VEHICLE")
        assertThat(catalog.units).allSatisfy { definition ->
            assertThat(definition.buyCredits).isPositive()
            assertThat(definition.upgradeMaterials).isPositive()
            assertThat(GameLanguage.entries.map(definition::name)).allSatisfy { assertThat(it).isNotBlank() }
            assertThat(definition.spatial.movementPoints).isBetween(1, 4)
            assertThat(definition.spatial.minimumRange).isBetween(1, definition.spatial.weaponRange)
            assertThat(definition.spatial.sightRange).isBetween(1, 6)
        }
    }

    @Test
    fun `personal map movement stays proportional to a nine by twelve sector`() {
        assertThat(catalog.require("RECON_VEHICLE").spatial.movementPoints).isEqualTo(4)
        assertThat(catalog.require("LIGHT_ARMOR").spatial.movementPoints).isEqualTo(4)
        assertThat(catalog.require("ARTILLERY").spatial.movementPoints).isEqualTo(2)
        assertThat(catalog.units.maxOf { it.spatial.movementPoints }).isEqualTo(4)
    }

    @Test
    fun `level scaling is integer deterministic and capped`() {
        val base = catalog.require("MBT").stats
        assertThat(base.scaled(1)).isEqualTo(base)
        assertThat(base.scaled(5).attack).isEqualTo(base.attack * 148 / 100)
        assertThat(base.scaled(99)).isEqualTo(base.scaled(5))
    }

    @Test
    fun `compact daily base reward stays close to three full starter groups`() {
        val starterCost = listOf("MBT", "MBT", "ARTILLERY", "RECON_VEHICLE").sumOf { catalog.require(it).buyCredits }
        assertThat(DailyRewardPolicy.BASE_CREDITS).isEqualTo(90L)
        assertThat(starterCost.toLong() * 3 - DailyRewardPolicy.BASE_CREDITS).isLessThanOrEqualTo(starterCost.toLong() * 3 / 10)
    }
}
