package com.tggames.frontline.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.progression.ForceTierCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

class SpatialBattleEngineTest {
    private val objectMapper = jacksonObjectMapper()
    private val equipment = EquipmentCatalog(objectMapper)
    private val maps = BattleMapCatalog(objectMapper)
    private val forceTiers = ForceTierCatalog(objectMapper)
    private val spatial = SpatialBattleEngine(equipment, forceTiers)
    private var snapshotSequence = 0

    @Test
    fun `map catalog covers every current biome with entries and capturable objectives`() {
        val biomes = setOf("горы", "речная долина", "побережье", "холмистая местность", "пустыня", "степь", "лес", "тундра", "равнина", "джунгли", "болота")

        biomes.forEach { biome ->
            val map = maps.forBiome(biome)
            assertThat(map.playerEntries).hasSizeGreaterThanOrEqualTo(3)
            assertThat(map.objectives).hasSizeGreaterThanOrEqualTo(3)
            assertThat(map.objectives).allMatch { it.captureSteps >= 2 }
        }
    }

    @Test
    fun `artillery can fire over a mountain when a scout spots the target but a tank cannot`() {
        val map = maps.forBiome("горы")
        val target = snapshot("MBT")
        val observer = snapshot("RECON_VEHICLE")
        val targetPosition = HexCoord(3, 3)
        val observerPosition = HexCoord(3, 4)

        assertThat(
            spatial.canAttack(snapshot("ARTILLERY"), HexCoord(0, 3), target, targetPosition, listOf(observer to observerPosition), map),
        ).isTrue()
        assertThat(
            spatial.canAttack(snapshot("MBT"), HexCoord(0, 3), target, targetPosition, listOf(observer to observerPosition), map),
        ).isFalse()
    }

    @Test
    fun `aircraft movement and weapons do not cover the whole map`() {
        val map = maps.forBiome("равнина")
        val diameter = HexCoord(0, 0).distanceTo(HexCoord(map.width - 1, map.height - 1))

        listOf("ATTACK_AIRCRAFT", "FIGHTER").forEach { code ->
            val profile = equipment.require(code).spatial
            assertThat(profile.weaponRange).isLessThan(diameter)
            assertThat(profile.movementPoints).isLessThan(diameter)
        }
    }

    @Test
    fun `selected entry and objective are preserved and change the opening route`() {
        val map = maps.forBiome("равнина")
        val operation = OperationOffer(0, Battlefield("Великие равнины", "равнина"), EnemyArchetype.FORTIFIED, Difficulty.STANDARD)
        val group = CombatGroupSnapshot(stableUuid("route-group"), 1, 6, listOf(snapshot("MBT"), snapshot("RECON_VEHICLE")))
        val southernPlan = DeploymentPlan("S", "crossing")
        val westernPlan = DeploymentPlan("W", "signal")

        val southern = spatial.simulate(91L, 1, operation, Tactic.ASSAULT, group, southernPlan, map)
        val western = spatial.simulate(91L, 1, operation, Tactic.ASSAULT, group, westernPlan, map)
        val firstSouthernMove = southern.events.first { it.side == BattleSide.PLAYER && it.type == SpatialEventType.UNIT_MOVED }
        val firstWesternMove = western.events.first { it.side == BattleSide.PLAYER && it.type == SpatialEventType.UNIT_MOVED }

        assertThat(southern.playerPlan).isEqualTo(southernPlan)
        assertThat(western.playerPlan).isEqualTo(westernPlan)
        assertThat(firstSouthernMove.from).isEqualTo(map.playerEntries.first { it.id == "S" }.position)
        assertThat(firstWesternMove.from).isEqualTo(map.playerEntries.first { it.id == "W" }.position)
        assertThat(firstWesternMove).isNotEqualTo(firstSouthernMove)
    }

    @Test
    fun `an objective changes owner only after uninterrupted capture progress`() {
        val map = maps.forBiome("речная долина")
        val operation = OperationOffer(0, Battlefield("Дунайская долина", "речная долина"), EnemyArchetype.ARTILLERY, Difficulty.STANDARD)
        val group = CombatGroupSnapshot(
            stableUuid("group"),
            1,
            10,
            listOf(snapshot("MBT"), snapshot("MBT"), snapshot("ARTILLERY"), snapshot("RECON_VEHICLE")),
        )
        val result = (1L..40L).asSequence().map { seed ->
            spatial.simulate(seed, 1, operation, Tactic.ASSAULT, group, DeploymentPlan("S", "crossing"), map)
        }.first { candidate -> candidate.events.any { it.type == SpatialEventType.OBJECTIVE_CAPTURED } }
        val captured = result.events.first { it.type == SpatialEventType.OBJECTIVE_CAPTURED }
        val progress = result.events.filter {
            it.type == SpatialEventType.CAPTURE_PROGRESS && it.objectiveId == captured.objectiveId && it.side == captured.side && it.step <= captured.step
        }

        assertThat(progress.takeLast(2).map { it.amount }).containsExactly(1, 2)
    }

    @Test
    fun `a captured objective can be taken back after its defenders are removed`() {
        val state = ObjectiveCaptureState(
            StrategicObjective("signal", "objective_signal_tower", HexCoord(2, 2), captureSteps = 2),
        )

        assertThat(state.advance(setOf(BattleSide.PLAYER))?.captured).isFalse()
        assertThat(state.advance(setOf(BattleSide.PLAYER))?.captured).isTrue()
        assertThat(state.owner).isEqualTo(BattleSide.PLAYER)

        assertThat(state.advance(setOf(BattleSide.ENEMY))?.captured).isFalse()
        assertThat(state.owner).isEqualTo(BattleSide.PLAYER)
        assertThat(state.advance(emptySet())).isNull()
        assertThat(state.advance(setOf(BattleSide.ENEMY))?.captured).isFalse()
        assertThat(state.advance(setOf(BattleSide.ENEMY))?.captured).isTrue()
        assertThat(state.owner).isEqualTo(BattleSide.ENEMY)
    }

    @Test
    fun `battle ends through objective control or loss of combat capability`() {
        val map = maps.forBiome("равнина")
        val operation = OperationOffer(0, Battlefield("Великие равнины", "равнина"), EnemyArchetype.MOBILE, Difficulty.STANDARD)
        val group = CombatGroupSnapshot(stableUuid("group-2"), 1, 6, listOf(snapshot("MBT"), snapshot("MBT")))
        val result = spatial.simulate(771L, 2, operation, Tactic.MANEUVER, group, DeploymentPlan("W", "signal"), map)

        when (result.endReason) {
            SpatialEndReason.ALL_OBJECTIVES_CAPTURED -> assertThat(result.objectives.map { it.owner }.distinct()).containsExactly(result.winner)
            SpatialEndReason.ARMY_DESTROYED -> {
                val defeated = if (result.winner == BattleSide.PLAYER) result.enemyUnits else result.playerUnits
                assertThat(defeated).allMatch { it.hitPoints == 0 || it.routed }
            }
            SpatialEndReason.ARMY_ROUTED -> {
                val defeated = if (result.winner == BattleSide.PLAYER) result.enemyUnits else result.playerUnits
                assertThat(defeated).allMatch { it.hitPoints == 0 || it.routed }
            }
        }
        assertThat(result.playerUnits + result.enemyUnits).allSatisfy { unit ->
            assertThat(unit.remainingQuantity).isEqualTo(if (unit.hitPoints <= 0) 0 else (unit.hitPoints + 99) / 100)
        }
    }

    @Test
    fun `corps battle aggregates a thousand command points into bounded formations`() {
        val map = maps.forBiome("равнина")
        val operation = OperationOffer(0, Battlefield("Великие равнины", "равнина"), EnemyArchetype.ARMOR, Difficulty.STANDARD)
        val formation = snapshot("RECON_VEHICLE").copy(cpCost = 1_000, quantity = 1_000)
        val group = CombatGroupSnapshot(stableUuid("corps"), 1, 1_000, listOf(formation))

        val result = spatial.simulate(991L, 50, operation, Tactic.MANEUVER, group, DeploymentPlan("S", "crossing"), map)

        assertThat(result.steps).isBetween(1, SpatialBattleEngine.MAX_STEPS)
        assertThat(result.playerUnits).hasSize(1)
        assertThat(result.playerUnits.single().quantity).isEqualTo(1_000)
        assertThat(result.enemyGroup.usedCp).isBetween(501, 1_000)
        assertThat(result.enemyGroup.units).hasSizeLessThanOrEqualTo(equipment.units.size)
    }

    private fun snapshot(code: String): UnitBattleSnapshot {
        val definition = equipment.require(code)
        val stats = definition.stats
        return UnitBattleSnapshot(
            id = stableUuid("$code-${snapshotSequence++}"),
            code = code,
            level = 1,
            cpCost = definition.cpCost,
            attack = stats.attack,
            armor = stats.armor,
            mobility = stats.mobility,
            recon = stats.recon,
            support = stats.support,
            roles = definition.roles,
            movementProfile = definition.spatial.movementProfile,
            movementPoints = definition.spatial.movementPoints,
            weaponRange = definition.spatial.weaponRange,
            minimumRange = definition.spatial.minimumRange,
            sightRange = definition.spatial.sightRange,
            fireMode = definition.spatial.fireMode,
        )
    }

    private fun stableUuid(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))
}
