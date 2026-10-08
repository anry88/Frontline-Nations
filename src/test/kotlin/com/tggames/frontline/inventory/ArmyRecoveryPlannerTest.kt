package com.tggames.frontline.inventory

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ArmyRecoveryPlannerTest {
    private val catalog = EquipmentCatalog(jacksonObjectMapper())

    @Test
    fun `uses free owned equipment before the cheapest valid purchase`() {
        val activeUnit = unit("MBT")
        val freeUnit = unit("MBT")
        val active = group(1, active = true, version = 7, units = listOf(activeUnit))
        val army = Army(1, 10, listOf(active), listOf(activeUnit, freeUnit))

        val plan = ArmyRecoveryPlanner.plan(army, catalog.units, 1, 10)

        assertThat(plan!!.ownedUnitIds).containsExactly(freeUnit.id)
        assertThat(plan.purchases.map { it.code }).containsExactly("MBT", "RECON_VEHICLE")
        assertThat(plan.purchaseCredits).isEqualTo(13)
        assertThat(plan.finalCp).isEqualTo(10)
    }

    @Test
    fun `empty starter level group gets the cheapest exact ten cp package`() {
        val active = group(1, active = true, version = 2, units = emptyList())
        val army = Army(1, 10, listOf(active), emptyList())

        val plan = ArmyRecoveryPlanner.plan(army, catalog.units, 1, 10)

        assertThat(plan!!.purchases.associate { it.code to it.quantity })
            .containsExactlyInAnyOrderEntriesOf(mapOf("MBT" to 3, "RECON_VEHICLE" to 1))
        assertThat(plan.purchaseCredits).isEqualTo(31)
    }

    @Test
    fun `reserved and other preset units are never taken`() {
        val reserved = unit("MBT", "2026-W41")
        val otherUnit = unit("MBT")
        val active = group(1, active = true, units = listOf(reserved))
        val other = group(2, active = false, units = listOf(otherUnit))
        val army = Army(1, 10, listOf(active, other), listOf(reserved, otherUnit))

        assertThat(ArmyRecoveryPlanner.plan(army, catalog.units, 1, 10)).isNull()
    }

    @Test
    fun `plan never includes equipment locked above commander level`() {
        val active = group(1, active = true, units = emptyList())
        val army = Army(1, 10, listOf(active), emptyList())

        val plan = ArmyRecoveryPlanner.plan(army, catalog.units, 1, 10)

        assertThat(plan!!.purchases.map { it.code }).doesNotContain("ATTACK_AIRCRAFT", "FIGHTER", "AIR_DEFENSE")
    }

    @Test
    fun `confirmation signature changes with composition or price`() {
        val first = ArmyRecoveryPlan(2, 3, 10, emptyList(), 0, listOf(RecoveryPurchase("MBT", 2, 6, 18), RecoveryPurchase("RECON_VEHICLE", 1, 1, 4)), 22)
        val changed = first.copy(purchases = listOf(RecoveryPurchase("LIGHT_ARMOR", 3, 6, 21), RecoveryPurchase("RECON_VEHICLE", 1, 1, 4)), purchaseCredits = 25)

        assertThat(first.signature).hasSize(12).isNotEqualTo(changed.signature)
    }

    @Test
    fun `recovery guidance exists for all supported locales`() {
        val keys = listOf(
            "recovery_title", "recovery_inventory_state", "recovery_reserved_reason", "recovery_no_valid_plan",
            "recovery_target", "recovery_owned_plan", "recovery_purchase_plan", "recovery_shortage_daily",
            "recovery_shortage_wait", "recovery_confirm_purchase", "recovery_confirm_owned", "recovery_complete",
            "recovery_changed_resources", "recovery_stale", "purchase_shortage_daily", "purchase_shortage_wait",
            "purchase_locked_next",
        )
        GameLanguage.entries.forEach { language ->
            keys.forEach { key -> assertThat(GameI18n.t(language, key, "1", "2", "3", "4", "5")).isNotBlank() }
        }
    }

    private fun unit(code: String, reserved: String? = null) = OwnedUnit(UUID.randomUUID(), code, 1, 100, "TEST", reserved)
    private fun group(preset: Int, active: Boolean, version: Int = 1, units: List<OwnedUnit>) =
        BattleGroup(UUID.randomUUID(), preset, "G$preset", active, version, units)
}
