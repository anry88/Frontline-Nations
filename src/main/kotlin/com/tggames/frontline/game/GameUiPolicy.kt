package com.tggames.frontline.game

import com.tggames.frontline.catalog.EquipmentDefinition
import com.tggames.frontline.inventory.Army
import java.time.LocalDate

data class EquipmentSelectionAvailability(
    val code: String,
    val selected: Int,
    val available: Int,
)

object GameUiPolicy {
    fun shopOrder(definitions: List<EquipmentDefinition>, commanderLevel: Int): List<EquipmentDefinition> =
        definitions.sortedBy { if (it.unlockLevel <= commanderLevel) 0 else 1 }

    fun equipmentSelection(army: Army): Map<String, EquipmentSelectionAvailability> {
        val availableUnits = army.inventory.filter { it.reservedWeekKey == null }
        val selectedUnits = army.activeGroup.units.filter { it.reservedWeekKey == null }
        return availableUnits.map { it.code }.distinct().associateWith { code ->
            EquipmentSelectionAvailability(
                code = code,
                selected = selectedUnits.count { it.code == code },
                available = availableUnits.count { it.code == code },
            )
        }
    }

    fun dailyRewardAvailable(lastClaim: LocalDate?, today: LocalDate): Boolean = lastClaim != today
}
