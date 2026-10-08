package com.tggames.frontline.game

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.Difficulty
import com.tggames.frontline.battle.EnemyArchetype
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.Tactic
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class OnboardingVariant(val value: String) {
    LEGACY("legacy"),
    GUIDED_V1("guided_v1"),
    ;

    companion object {
        fun fromStored(value: String?): OnboardingVariant? = entries.firstOrNull { it.value == value }
    }
}

internal data class FirstMissionRecommendation(
    val version: Int,
    val operation: OperationOffer,
    val map: BattleMapDefinition,
    val entryId: String,
    val objectiveId: String,
    val tactic: Tactic,
)

internal enum class FirstMissionReadiness { READY, EMPTY, RESERVED, OUTSIDE_CP_LIMIT }

internal fun firstMissionReadiness(
    unitCount: Int,
    hasReservedUnits: Boolean,
    usedCp: Int,
    cpLimit: Int,
    minimumBattleCp: Int,
): FirstMissionReadiness = when {
    unitCount == 0 -> FirstMissionReadiness.EMPTY
    hasReservedUnits -> FirstMissionReadiness.RESERVED
    usedCp < minimumBattleCp || usedCp > cpLimit -> FirstMissionReadiness.OUTSIDE_CP_LIMIT
    else -> FirstMissionReadiness.READY
}

internal object FirstMissionRecommendationPolicy {
    const val VERSION = 1

    fun assignedVariant(telegramId: Long, rolloutPercent: Int): OnboardingVariant {
        require(rolloutPercent in 0..100) { "First-mission rollout must be between 0 and 100" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("frontline-first-mission-v1:$telegramId".toByteArray(StandardCharsets.UTF_8))
        val bucket = Integer.remainderUnsigned(ByteBuffer.wrap(digest).int, 100)
        return if (bucket < rolloutPercent) OnboardingVariant.GUIDED_V1 else OnboardingVariant.LEGACY
    }

    fun recommend(
        offers: List<OperationOffer>,
        commanderLevel: Int,
        group: CombatGroupSnapshot,
        mapFor: (OperationOffer) -> BattleMapDefinition,
    ): FirstMissionRecommendation {
        require(offers.isNotEmpty()) { "At least one operation is required" }
        require(group.units.isNotEmpty()) { "A non-empty group is required" }
        val roles = group.units.flatMap { it.roles }.toSet()
        val operation = offers.maxWithOrNull(
            compareBy<OperationOffer> { operationScore(it, commanderLevel, roles) }
                .thenBy { -it.slot },
        )!!
        val tactic = Tactic.entries.maxWithOrNull(
            compareBy<Tactic> { recommendationFit(it, group) + counterOrderScore(it, operation.enemy) }
                .thenBy { -it.ordinal },
        )!!
        val map = mapFor(operation)
        val route = map.playerEntries.flatMap { entry ->
            map.objectives.map { objective -> Triple(entry.id, objective.id, map.distanceBetween(entry.position, objective.position)) }
        }.minWithOrNull(compareBy<Triple<String, String, Int>> { it.third }.thenBy { it.first }.thenBy { it.second })
            ?: error("First-mission map must contain an entry and an objective")
        return FirstMissionRecommendation(VERSION, operation, map, route.first, route.second, tactic)
    }

    private fun operationScore(operation: OperationOffer, commanderLevel: Int, roles: Set<String>): Int {
        val preferredDifficulty = if (commanderLevel <= 2) Difficulty.SCOUTED else Difficulty.STANDARD
        val difficulty = when {
            operation.difficulty == preferredDifficulty -> 40
            operation.difficulty == Difficulty.SCOUTED -> 30
            operation.difficulty == Difficulty.STANDARD -> 20
            else -> 0
        }
        val readiness = when (operation.enemy) {
            EnemyArchetype.ARMOR -> if ("FIREPOWER" in roles || "AIR" in roles) 8 else -8
            EnemyArchetype.ARTILLERY -> if ("MOBILE" in roles && "RECON" in roles) 8 else -6
            EnemyArchetype.FORTIFIED -> if ("FIREPOWER" in roles) 7 else -7
            EnemyArchetype.AMBUSH -> if ("RECON" in roles) 9 else -9
            EnemyArchetype.MOBILE -> if ("MOBILE" in roles || "AIR" in roles) 7 else -6
            EnemyArchetype.AIR -> if ("AIR_DEFENSE" in roles) 10 else -10
            EnemyArchetype.AIR_DEFENSE -> if ("ARMOR" in roles || "FIREPOWER" in roles) 7 else -7
        }
        return difficulty + readiness
    }

    private fun recommendationFit(tactic: Tactic, group: CombatGroupSnapshot): Int {
        val units = group.units.sumOf { it.quantity }.coerceAtLeast(1)
        val roles = group.units.flatMap { it.roles }.toSet()
        val required = when (tactic) {
            Tactic.ASSAULT -> setOf("ARMOR", "FIREPOWER")
            Tactic.DEFENSE -> setOf("ARMOR", "SUPPORT")
            Tactic.AMBUSH -> setOf("RECON", "FIREPOWER")
            Tactic.MANEUVER -> setOf("MOBILE", "ARMOR")
            Tactic.RECON -> setOf("RECON")
        }
        val weighted = group.units.sumOf { unit ->
            val score = when (tactic) {
                Tactic.ASSAULT -> unit.attack * 45 + unit.armor * 35 + unit.mobility * 20
                Tactic.DEFENSE -> unit.armor * 45 + unit.support * 35 + unit.attack * 20
                Tactic.AMBUSH -> unit.recon * 40 + unit.attack * 35 + unit.mobility * 25
                Tactic.MANEUVER -> unit.mobility * 45 + unit.recon * 25 + unit.attack * 30
                Tactic.RECON -> unit.recon * 55 + unit.mobility * 30 + unit.support * 15
            }
            score * unit.quantity
        } / (100 * units)
        return weighted + if (roles.containsAll(required)) 10 else -15
    }

    private fun counterOrderScore(tactic: Tactic, enemy: EnemyArchetype): Int = when (tactic) {
        Tactic.ASSAULT -> if (enemy == EnemyArchetype.ARTILLERY) 8 else if (enemy == EnemyArchetype.FORTIFIED) -6 else 1
        Tactic.DEFENSE -> if (enemy in setOf(EnemyArchetype.ARMOR, EnemyArchetype.MOBILE, EnemyArchetype.AIR)) 8 else if (enemy == EnemyArchetype.ARTILLERY) -6 else 1
        Tactic.AMBUSH -> if (enemy in setOf(EnemyArchetype.ARMOR, EnemyArchetype.MOBILE)) 8 else if (enemy == EnemyArchetype.AMBUSH) -5 else 1
        Tactic.MANEUVER -> if (enemy in setOf(EnemyArchetype.ARTILLERY, EnemyArchetype.FORTIFIED, EnemyArchetype.AIR_DEFENSE)) 8 else if (enemy == EnemyArchetype.MOBILE) -5 else 1
        Tactic.RECON -> if (enemy == EnemyArchetype.AMBUSH) 9 else if (enemy == EnemyArchetype.ARMOR) -4 else 2
    }
}

internal data class FirstMissionCallback(
    val action: String,
    val recommendationVersion: Int,
    val offerVersion: Long,
)

internal fun parseFirstMissionCallback(data: String): FirstMissionCallback? {
    val parts = data.split(':')
    if (parts.size != 4 || parts[0] != "first" || parts[1] !in setOf("start", "configure")) return null
    return FirstMissionCallback(
        action = parts[1],
        recommendationVersion = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null,
        offerVersion = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null,
    )
}
