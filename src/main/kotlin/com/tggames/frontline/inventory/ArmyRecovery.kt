package com.tggames.frontline.inventory

import com.tggames.frontline.catalog.EquipmentDefinition
import java.util.UUID
import java.security.MessageDigest

data class RecoveryPurchase(val code: String, val quantity: Int, val cp: Int, val credits: Int)

data class ArmyRecoveryPlan(
    val groupVersion: Int,
    val currentCp: Int,
    val targetCp: Int,
    val ownedUnitIds: List<UUID>,
    val ownedCp: Int,
    val purchases: List<RecoveryPurchase>,
    val purchaseCredits: Int,
) {
    val finalCp: Int get() = currentCp + ownedCp + purchases.sumOf { it.cp }
    val usesOwned: Boolean get() = ownedUnitIds.isNotEmpty()
    val needsPurchase: Boolean get() = purchases.isNotEmpty()
    val signature: String get() {
        val canonical = buildString {
            append(groupVersion).append('|').append(currentCp).append('|').append(targetCp).append('|')
            append(ownedUnitIds.sorted().joinToString(",")).append('|')
            append(purchases.sortedBy { it.code }.joinToString(",") { "${it.code}:${it.quantity}:${it.credits}" })
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}

object ArmyRecoveryPlanner {
    fun plan(
        army: Army,
        definitions: List<EquipmentDefinition>,
        commanderLevel: Int,
        minimumBattleCp: Int,
        maxGroupUnits: Int = InventoryService.MAX_GROUP_SLOTS,
    ): ArmyRecoveryPlan? {
        val active = army.activeGroup
        if (active.units.any { it.reservedWeekKey != null }) return null
        val currentCp = active.units.sumOf { unit -> definitions.first { it.code == unit.code }.cpCost }
        if (currentCp >= minimumBattleCp || currentCp > army.cpLimit) return null
        val assigned = army.groups.flatMap { it.units }.mapTo(mutableSetOf()) { it.id }
        val available = army.inventory.filter { it.id !in assigned && it.reservedWeekKey == null }
        val remainingSlots = (maxGroupUnits - active.units.size).coerceAtLeast(0)
        val owned = bestOwnedSubset(available, definitions, currentCp, army.cpLimit, minimumBattleCp, remainingSlots)
        val ownedCp = owned.sumOf { unit -> definitions.first { it.code == unit.code }.cpCost }
        val slotsAfterOwned = remainingSlots - owned.size
        val purchases = cheapestPurchases(
            definitions.filter { it.unlockLevel <= commanderLevel },
            currentCp + ownedCp,
            army.cpLimit,
            minimumBattleCp,
            slotsAfterOwned,
        ) ?: return null
        return ArmyRecoveryPlan(
            groupVersion = active.version,
            currentCp = currentCp,
            targetCp = minimumBattleCp,
            ownedUnitIds = owned.map { it.id },
            ownedCp = ownedCp,
            purchases = purchases,
            purchaseCredits = purchases.sumOf { it.credits },
        ).takeIf { it.finalCp >= minimumBattleCp }
    }

    private fun bestOwnedSubset(
        units: List<OwnedUnit>,
        definitions: List<EquipmentDefinition>,
        currentCp: Int,
        cpLimit: Int,
        minimumBattleCp: Int,
        maxUnits: Int,
    ): List<OwnedUnit> {
        var states = mapOf(0 to emptyList<OwnedUnit>())
        units.sortedBy { it.id }.forEach { unit ->
            val cp = definitions.first { it.code == unit.code }.cpCost
            val additions = states.mapNotNull { (sum, selected) ->
                (sum + cp).takeIf { currentCp + it <= cpLimit && selected.size < maxUnits }?.let { it to (selected + unit) }
            }
            states = (states.entries.map { it.key to it.value } + additions)
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, options) -> options.minBy { it.size } }
        }
        return states.entries
            .sortedWith(compareBy<Map.Entry<Int, List<OwnedUnit>>> { if (currentCp + it.key >= minimumBattleCp) 0 else 1 }
                .thenBy { kotlin.math.abs(minimumBattleCp - currentCp - it.key) }
                .thenBy { it.value.size })
            .first().value
    }

    private fun cheapestPurchases(
        definitions: List<EquipmentDefinition>,
        currentCp: Int,
        cpLimit: Int,
        minimumBattleCp: Int,
        maxUnits: Int,
    ): List<RecoveryPurchase>? {
        if (currentCp >= minimumBattleCp) return emptyList()
        data class Candidate(val credits: Int, val codes: List<String>)
        val states = mutableMapOf(currentCp to Candidate(0, emptyList()))
        for (cp in currentCp..cpLimit) {
            val candidate = states[cp] ?: continue
            if (candidate.codes.size >= maxUnits) continue
            definitions.forEach { definition ->
                val nextCp = cp + definition.cpCost
                if (nextCp <= cpLimit) {
                    val value = Candidate(candidate.credits + definition.buyCredits, candidate.codes + definition.code)
                    val existing = states[nextCp]
                    if (existing == null || compareValuesBy(value, existing, Candidate::credits, { it.codes.size }, { it.codes.joinToString() }) < 0) {
                        states[nextCp] = value
                    }
                }
            }
        }
        val winner = states.filterKeys { it >= minimumBattleCp }.entries
            .minWithOrNull(compareBy<Map.Entry<Int, Candidate>> { it.value.credits }.thenBy { it.key }.thenBy { it.value.codes.size })
            ?: return null
        return winner.value.codes.groupingBy { it }.eachCount().toSortedMap().map { (code, quantity) ->
            val definition = definitions.first { it.code == code }
            RecoveryPurchase(code, quantity, definition.cpCost * quantity, definition.buyCredits * quantity)
        }
    }
}

enum class RecoveryApplyStatus { APPLIED, STALE, RESERVED, INSUFFICIENT_CREDITS, UNAVAILABLE }

data class RecoveryApplyResult(val status: RecoveryApplyStatus, val plan: ArmyRecoveryPlan? = null)
