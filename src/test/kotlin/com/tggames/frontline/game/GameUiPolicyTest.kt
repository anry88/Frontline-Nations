package com.tggames.frontline.game

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.inventory.Army
import com.tggames.frontline.inventory.BattleGroup
import com.tggames.frontline.inventory.OwnedUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class GameUiPolicyTest {
    private val equipment = EquipmentCatalog(jacksonObjectMapper())

    @Test
    fun `shop puts every unlocked class before locked classes`() {
        val ordered = GameUiPolicy.shopOrder(equipment.units, commanderLevel = 2)

        assertThat(ordered.map { it.unlockLevel <= 2 }).doesNotContainSequence(false, true)
        assertThat(ordered.filter { it.unlockLevel <= 2 }.map { it.code })
            .contains("RECON_VEHICLE")
    }

    @Test
    fun `front reserved units are absent from army selection counts`() {
        val free = unit("MBT")
        val reserved = unit("RECON_VEHICLE", "2026-W37")
        val group = BattleGroup(UUID.randomUUID(), 1, "Alpha", true, 1, listOf(free, reserved))
        val army = Army(1, 10, listOf(group), listOf(free, reserved))

        val availability = GameUiPolicy.equipmentSelection(army)

        assertThat(availability).containsOnlyKeys("MBT")
        assertThat(availability.getValue("MBT").selected).isEqualTo(1)
        assertThat(availability.getValue("MBT").available).isEqualTo(1)
    }

    @Test
    fun `daily button is available only before todays claim`() {
        val today = LocalDate.of(2026, 9, 11)

        assertThat(GameUiPolicy.dailyRewardAvailable(null, today)).isTrue()
        assertThat(GameUiPolicy.dailyRewardAvailable(today.minusDays(1), today)).isTrue()
        assertThat(GameUiPolicy.dailyRewardAvailable(today, today)).isFalse()
    }

    private fun unit(code: String, reservedWeek: String? = null) = OwnedUnit(
        id = UUID.randomUUID(),
        code = code,
        level = 1,
        durability = 100,
        origin = "TEST",
        reservedWeekKey = reservedWeek,
    )
}
