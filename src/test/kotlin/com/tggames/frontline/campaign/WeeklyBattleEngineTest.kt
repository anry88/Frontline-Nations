package com.tggames.frontline.campaign

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.battle.Tactic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class WeeklyBattleEngineTest {
    private val objectMapper = jacksonObjectMapper()
    private val equipment = EquipmentCatalog(objectMapper)
    private val engine = WeeklyBattleEngine(equipment)
    private val maps = WeeklyBattleMapCatalog(objectMapper).maps
    private val map = maps.first()
    private val balance = WeeklyBalance()

    @Test
    fun `same weekly inputs produce the same battle and events`() {
        val a = AllianceForce("RS", listOf(WeeklyUnitContribution("MBT", 2, 4, 101)), 1, 1_200)
        val b = AllianceForce("BR", listOf(WeeklyUnitContribution("ARTILLERY", 1, 3, 202)), 1, 900)

        val first = engine.resolve("secret", "2026-W37", 0, map, a, b, balance)
        val second = engine.resolve("secret", "2026-W37", 0, map, a, b, balance)

        assertThat(second).isEqualTo(first)
        assertThat(first.completedTicks).isBetween(1, balance.maxTicks)
        assertThat(first.objectives).hasSize(5)
        assertThat(first.scoreA).isEqualTo(first.objectiveScoreA + first.destroyedScoreA + first.survivorScoreA)
        assertThat(first.seedHash).hasSize(64)
        assertThat(first.winnerCode).isIn("RS", "BR")
        assertThat(first.contributionPerformance.map { it.playerId }).containsExactly(101, 202)
        assertThat(first.formations.mapNotNull { it.contributorPlayerId }).contains(101, 202)
        assertThat(first.formations.map { it.id }).doesNotHaveDuplicates().allMatch { it.isNotBlank() }
        assertThat(first.formations).allMatch { it.initialPosition != null }
        assertThat(first.events).anyMatch { it.type == WeeklyEventType.FORMATION_MOVED && it.from != it.to }
        assertThat(first.events).anyMatch {
            it.type == WeeklyEventType.FORMATION_HIT && it.formationId != null && it.targetFormationId != null && it.amount > 0
        }
        first.contributionPerformance.forEach { performance ->
            assertThat(performance.destroyedPower).isEqualTo(
                first.events.filter {
                    it.type == WeeklyEventType.FORMATION_DESTROYED && performance.playerId in it.contributorPlayerIds
                }.sumOf { it.destroyedPower },
            )
            assertThat(performance.capturedObjectives).isEqualTo(
                first.events.count {
                    it.type == WeeklyEventType.OBJECTIVE_CAPTURED && performance.playerId in it.contributorPlayerIds
                },
            )
        }
    }

    @Test
    fun `every country receives a deterministic first tier npc squad`() {
        val emptyA = AllianceForce("RS", emptyList(), 0, 0)
        val emptyB = AllianceForce("BR", emptyList(), 0, 0)
        val result = engine.resolve("secret", "2026-W37", 4, map, emptyA, emptyB, balance)

        assertThat(result.npcBonusA).isBetween(10, 25)
        assertThat(result.npcBonusB).isBetween(10, 25)
        assertThat(result.effectivePowerA).isBetween(1_000, 2_500)
        assertThat(result.effectivePowerB).isBetween(1_000, 2_500)
        assertThat(result.effectivePowerA).isEqualTo(result.npcBonusA * 100L)
        assertThat(result.effectivePowerB).isEqualTo(result.npcBonusB * 100L)
        assertThat(result.npcUnitsA.sumOf { equipment.require(it.code).cpCost * it.quantity }).isEqualTo(result.npcBonusA)
    }

    @Test
    fun `late capture is worth less but never below floor`() {
        assertThat(engine.capturePoints(1, balance)).isGreaterThan(engine.capturePoints(20, balance))
        assertThat(engine.capturePoints(10_000, balance)).isEqualTo(balance.objectiveMinPoints.toLong())
    }

    @Test
    fun `engine version 7 preserves time limit winner ordering for current week`() {
        val winner = engine.winner(
            engineVersion = 7,
            reason = WeeklyEndReason.TIME_LIMIT,
            remainingA = 2_000,
            remainingB = 1_000,
            objectiveA = 500,
            objectiveB = 1_500,
            scoreA = 2_000,
            scoreB = 3_000,
            random = Random(1),
        )

        assertThat(winner).isEqualTo(WeeklySide.A)
    }

    @Test
    fun `engine version 8 decides time limit by total battle score`() {
        val winner = engine.winner(
            engineVersion = 8,
            reason = WeeklyEndReason.TIME_LIMIT,
            remainingA = 2_000,
            remainingB = 1_000,
            objectiveA = 500,
            objectiveB = 1_500,
            scoreA = 2_000,
            scoreB = 3_000,
            random = Random(1),
        )

        assertThat(winner).isEqualTo(WeeklySide.B)
    }

    @Test
    fun `engine version 8 keeps decisive end conditions authoritative`() {
        assertThat(
            engine.winner(8, WeeklyEndReason.ARMY_DESTROYED, 1, 0, 0, 4_000, 500, 4_500, Random(1)),
        ).isEqualTo(WeeklySide.A)
        assertThat(
            engine.winner(8, WeeklyEndReason.ALL_OBJECTIVES_CAPTURED, 0, 3_000, 4_000, 0, 4_000, 5_000, Random(1)),
        ).isEqualTo(WeeklySide.A)
    }

    @Test
    fun `engine version 8 resolves opposing formations sharing an objective hex`() {
        assertThat(engine.withinWeaponRange(7, distance = 0, weaponRange = 2)).isFalse()
        assertThat(engine.withinWeaponRange(8, distance = 0, weaponRange = 2)).isTrue()
        assertThat(engine.withinWeaponRange(8, distance = 3, weaponRange = 2)).isFalse()
    }

    @Test
    fun `each contributed preset keeps its selected entry and formation identity`() {
        val firstEntry = map.playerEntries.first()
        val lastEntry = map.playerEntries.last()
        val forceA = AllianceForce(
            "RS",
            listOf(
                WeeklyUnitContribution("MBT", 1, 1, 101, 501, firstEntry.id, Tactic.DEFENSE, map.objectives.first().id),
                WeeklyUnitContribution("MBT", 1, 1, 101, 502, lastEntry.id, Tactic.ASSAULT, map.objectives.last().id),
            ),
            1,
            600,
        )
        val result = engine.resolve("secret", "2026-W37", 7, map, forceA, AllianceForce("BR", emptyList(), 0, 0), balance)
        val playerFormations = result.formations.filter { it.contributorPlayerId == 101L }

        assertThat(playerFormations).hasSize(2)
        assertThat(playerFormations.map { it.sourceContributionId }).containsExactlyInAnyOrder(501, 502)
        assertThat(playerFormations.single { it.sourceContributionId == 501L }.initialPosition).isEqualTo(firstEntry.position)
        assertThat(playerFormations.single { it.sourceContributionId == 502L }.initialPosition).isEqualTo(lastEntry.position)
    }

    @Test
    fun `selected primary objective controls the contributed formation opening route`() {
        val entry = map.playerEntries.first()
        val target = map.objectives.last()
        val forceA = AllianceForce(
            "RS",
            listOf(WeeklyUnitContribution("MBT", 1, 1, 101, 501, entry.id, Tactic.MANEUVER, target.id)),
            1,
            300,
        )

        val result = engine.resolve("secret", "2026-W37", 7, map, forceA, AllianceForce("BR", emptyList(), 0, 0), balance)
        val firstMove = result.events.first { it.type == WeeklyEventType.FORMATION_MOVED && it.formationId?.contains(":501:") == true }

        assertThat(map.distanceBetween(requireNotNull(firstMove.to), target.position))
            .isLessThan(map.distanceBetween(requireNotNull(firstMove.from), target.position))
    }

    @Test
    fun `legacy contribution without primary objective still resolves`() {
        val legacy = AllianceForce(
            "RS",
            listOf(WeeklyUnitContribution("MBT", 1, 1, 101, 501, map.playerEntries.first().id, Tactic.MANEUVER)),
            1,
            300,
        )

        val result = engine.resolve("secret", "2026-W37", 8, map, legacy, AllianceForce("BR", emptyList(), 0, 0), balance)

        assertThat(result.formations).anyMatch { it.sourceContributionId == 501L }
    }

    @Test
    fun `all weekly maps resolve with catalog ranges and bounded duration`() {
        val forceA = AllianceForce("RS", listOf(WeeklyUnitContribution("FIGHTER", 3, 2), WeeklyUnitContribution("MBT", 2, 4)), 1, 1_800)
        val forceB = AllianceForce("BR", listOf(WeeklyUnitContribution("AIR_DEFENSE", 2, 3), WeeklyUnitContribution("ARTILLERY", 2, 4)), 1, 1_800)

        maps.forEachIndexed { index, weeklyMap ->
            val result = engine.resolve("secret", "2026-W37", index, weeklyMap, forceA, forceB, balance)
            assertThat(result.completedTicks).isBetween(1, balance.maxTicks)
            assertThat(result.formations).allSatisfy { formation ->
                assertThat(formation.weaponRange).isEqualTo(equipment.require(formation.unitCode).spatial.weaponRange)
            }
        }
    }
}
