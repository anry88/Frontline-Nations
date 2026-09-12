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
        val diameter = map.distanceBetween(HexCoord(0, 0), HexCoord(map.width - 1, map.height - 1))

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
    fun `ambush formations do not wait forever outside weapon range`() {
        val map = maps.forBiome("лес")
        val operation = OperationOffer(0, Battlefield("Полесский рубеж", "лес"), EnemyArchetype.AMBUSH, Difficulty.STANDARD)
        val firstLightArmor = snapshot("LIGHT_ARMOR").copy(
            id = UUID.fromString("0f68c47e-dd13-3a72-95c2-428b21206d6d"),
        )
        val upgradedLightArmorDefinition = equipment.require("LIGHT_ARMOR")
        val upgradedLightArmorStats = upgradedLightArmorDefinition.stats.scaled(2)
        val secondLightArmor = snapshot("LIGHT_ARMOR").copy(
            id = UUID.fromString("afb09601-d21a-3cf3-85ad-ada4e7a280c1"),
            level = 2,
            attack = upgradedLightArmorStats.attack,
            armor = upgradedLightArmorStats.armor,
            mobility = upgradedLightArmorStats.mobility,
            recon = upgradedLightArmorStats.recon,
            support = upgradedLightArmorStats.support,
        )
        val tanks = snapshot("MBT").copy(
            id = UUID.fromString("fd2af1f6-4082-3049-a60d-e24c5d509d1e"),
            cpCost = 6,
            quantity = 2,
        )
        val group = CombatGroupSnapshot(
            UUID.fromString("57cdaa3a-6daf-4a57-b2d0-8badac6921ec"),
            12,
            10,
            listOf(firstLightArmor, secondLightArmor, tanks),
        )

        val result = spatial.simulate(
            seed = -5404220392128359389L,
            commanderLevel = 1,
            operation = operation,
            tactic = Tactic.AMBUSH,
            group = group,
            plan = DeploymentPlan("W", "crossing"),
            map = map,
        )
        assertThat(result.steps).isLessThan(SpatialBattleEngine.MAX_STEPS)
        assertThat(result.events).anyMatch {
            it.type == SpatialEventType.CAPTURE_PROGRESS && it.side == BattleSide.PLAYER
        }
    }

    @Test
    fun `indirect fire cannot create a defensive deadlock`() {
        val map = maps.forBiome("тундра")
        val operation = OperationOffer(0, Battlefield("Северная тундра", "тундра"), EnemyArchetype.FORTIFIED, Difficulty.STANDARD)
        val artillery = snapshot("ARTILLERY").copy(
            id = UUID.fromString("43be0f1c-3ffe-3818-a9f4-7f8fe916e5a4"),
            cpCost = 9,
            quantity = 3,
        )
        val scouts = snapshot("RECON_VEHICLE").copy(
            id = UUID.fromString("116e50e7-1b62-3a7a-b5b4-3a9dc76db790"),
            cpCost = 2,
            quantity = 2,
        )
        val group = CombatGroupSnapshot(
            UUID.fromString("57cdaa3a-6daf-4a57-b2d0-8badac6921ec"),
            21,
            11,
            listOf(artillery, scouts),
        )

        val result = spatial.simulate(
            seed = -3014996747345208731L,
            commanderLevel = 2,
            operation = operation,
            tactic = Tactic.DEFENSE,
            group = group,
            plan = DeploymentPlan("W", "signal"),
            map = map,
        )
        val hitsOnEnemy = result.events.filter {
            it.type == SpatialEventType.UNIT_HIT && it.side == BattleSide.PLAYER && it.unitCode == "ARTILLERY"
        }

        assertThat(hitsOnEnemy).isNotEmpty()
        assertThat(result.steps).isLessThan(SpatialBattleEngine.MAX_STEPS)
    }

    @Test
    fun `defenders that trail on objectives advance instead of deadlocking`() {
        val map = maps.forBattlefield("Предгорья Атласа", "горы")
        val operation = OperationOffer(
            0,
            Battlefield("Предгорья Атласа", "горы"),
            EnemyArchetype.AIR_DEFENSE,
            Difficulty.SCOUTED,
        )
        val upgradedTankDefinition = equipment.require("MBT")
        val upgradedTankStats = upgradedTankDefinition.stats.scaled(2)
        val group = CombatGroupSnapshot(
            UUID.fromString("57cdaa3a-6daf-4a57-b2d0-8badac6921ec"),
            56,
            10,
            listOf(
                snapshot("ARTILLERY").copy(id = UUID.fromString("fca0b1b9-ef6d-3482-af50-85132826e742")),
                snapshot("MBT").copy(id = UUID.fromString("da6dc386-bf9e-3155-b754-212e98b28fb3")),
                snapshot("MBT").copy(
                    id = UUID.fromString("ec6a8818-e0e2-3a33-bcc8-664023881c94"),
                    level = 2,
                    attack = upgradedTankStats.attack,
                    armor = upgradedTankStats.armor,
                    mobility = upgradedTankStats.mobility,
                    recon = upgradedTankStats.recon,
                    support = upgradedTankStats.support,
                ),
                snapshot("RECON_VEHICLE").copy(id = UUID.fromString("1f1582dd-4423-3b2a-b875-eddb9a701e0f")),
            ),
        )

        val result = spatial.simulate(
            seed = -2629783787737138616L,
            commanderLevel = 2,
            operation = operation,
            tactic = Tactic.DEFENSE,
            group = group,
            plan = DeploymentPlan("W", "signal"),
            map = map,
        )

        assertThat(result.events).anyMatch {
            it.type == SpatialEventType.UNIT_MOVED &&
                it.side == BattleSide.PLAYER &&
                it.unitCode == "MBT" &&
                it.step > 4
        }
        assertThat(result.steps).isLessThan(SpatialBattleEngine.MAX_STEPS)
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
