package com.tggames.frontline.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.game.DailyRewardPolicy
import com.tggames.frontline.progression.ForceTierCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class BattleEngineTest {
    private val objectMapper = jacksonObjectMapper()
    private val equipment = EquipmentCatalog(objectMapper)
    private val maps = BattleMapCatalog(objectMapper)
    private val forceTiers = ForceTierCatalog(objectMapper)
    private val spatial = SpatialBattleEngine(equipment, forceTiers)
    private val engine = BattleEngine(spatial, maps, forceTiers)
    private val operation = OperationOffer(0, Battlefield("Дунайская долина", "речная долина"), EnemyArchetype.ARTILLERY, Difficulty.STANDARD)
    private val balancedGroup = group(
        unit("MBT", 3, 30, 32, 12, 5, 4, "ARMOR", "FIREPOWER"),
        unit("MBT", 3, 30, 32, 12, 5, 4, "ARMOR", "FIREPOWER"),
        unit("ARTILLERY", 3, 38, 6, 8, 4, 7, "FIREPOWER", "SUPPORT"),
        unit("RECON_VEHICLE", 1, 8, 6, 36, 40, 8, "RECON", "MOBILE"),
    )

    @Test
    fun `same snapshots produce exactly the same offers result and replay`() {
        assertThat(engine.offers("secret", "player:date:5")).isEqualTo(engine.offers("secret", "player:date:5"))
        val first = engine.resolve("secret", "player:battle:5", 3, operation, Tactic.MANEUVER, balancedGroup)
        val second = engine.resolve("secret", "player:battle:5", 3, operation, Tactic.MANEUVER, balancedGroup)
        assertThat(second).isEqualTo(first)
        assertThat(engine.replay(first.seed, 3, operation, Tactic.MANEUVER, balancedGroup)).isEqualTo(first)
    }

    @Test
    fun `operation board contains five choices drawn from a larger world catalog`() {
        val offers = engine.offers("secret", "player:date:4")
        assertThat(engine.battlefields).hasSize(24)
        assertThat(offers).hasSize(5)
        assertThat(offers.map { it.difficulty }).containsAll(Difficulty.entries)
        assertThat(offers.map { it.battlefield.location }).doesNotHaveDuplicates()
    }

    @Test
    fun `legacy v3 tactic assessment remains reproducible for historical battles`() {
        val armored = group(
            unit("MBT", 3, 32, 36, 12, 4, 3, "ARMOR", "FIREPOWER"),
            unit("ARTILLERY", 3, 40, 6, 8, 4, 8, "FIREPOWER", "SUPPORT"),
        )
        val scouts = group(
            unit("RECON_VEHICLE", 1, 8, 6, 38, 42, 8, "RECON", "MOBILE"),
            unit("LIGHT_ARMOR", 2, 18, 14, 32, 18, 5, "ARMOR", "MOBILE", "RECON"),
        )
        assertThat(engine.assess(Tactic.ASSAULT, armored).fit).isGreaterThan(engine.assess(Tactic.RECON, armored).fit)
        assertThat(engine.assess(Tactic.RECON, scouts).fit).isGreaterThan(engine.assess(Tactic.ASSAULT, scouts).fit)
    }

    @Test
    fun `legacy v3 assessment retains missing-role penalties`() {
        val artilleryOnly = group(unit("ARTILLERY", 3, 40, 5, 7, 3, 6, "FIREPOWER", "SUPPORT"))
        val ambush = engine.assess(Tactic.AMBUSH, artilleryOnly)
        assertThat(ambush.requirementsMet).isFalse()
        assertThat(ambush.missingRoles).contains("RECON")
        assertThat(ambush.bonus).isNegative()
    }

    @Test
    fun `unit upgrades increase composition power without changing command cost`() {
        val upgraded = balancedGroup.copy(units = balancedGroup.units.map {
            it.copy(level = 2, attack = it.attack * 112 / 100, armor = it.armor * 112 / 100, mobility = it.mobility * 112 / 100, recon = it.recon * 112 / 100, support = it.support * 112 / 100)
        })
        assertThat(engine.compositionPower(upgraded)).isGreaterThan(engine.compositionPower(balancedGroup))
        assertThat(upgraded.usedCp).isEqualTo(balancedGroup.usedCp)
    }

    @Test
    fun `battle produces bounded report and all rewards`() {
        val result = engine.resolve("secret", "another-battle", 1, operation, Tactic.RECON, balancedGroup)
        assertThat(result.events).hasSizeBetween(1, SpatialBattleEngine.MAX_STEPS)
        assertThat(result.playerPower).isPositive()
        assertThat(result.enemyPower).isPositive()
        assertThat(result.xp).isPositive()
        assertThat(result.credits).isPositive()
        assertThat(result.credits).isEqualTo(result.outcomeCredits + result.destructionCredits)
        assertThat(result.xp).isEqualTo(result.outcomeXp + result.destructionXp)
        assertThat(result.destructionCredits).isGreaterThanOrEqualTo(0)
        assertThat(result.destroyedEnemyUnits).isGreaterThanOrEqualTo(0)
        assertThat(result.materials).isPositive()
        assertThat(result.seedHash).hasSize(64)
        assertThat(result.spatial).isNotNull
        assertThat(result.tacticBonus).isZero()
        assertThat(result.terrainBonus).isZero()
        assertThat(result.counterBonus).isZero()
        assertThat(result.forceTierId).isEqualTo("detachment")
        assertThat(result.playerDeployedCp).isEqualTo(10)
        assertThat(result.enemyDeployedCp).isBetween(10, 25)
    }

    @Test
    fun `equal standard battles support five-fight daily economy`() {
        val outcomes = (1..30).map {
            engine.resolve("secret", "economy-$it", 1, operation, Tactic.MANEUVER, balancedGroup)
        }
        val victories = outcomes.filter { it.victory }
        val averageReplacementCost = outcomes.map { result ->
            requireNotNull(result.spatial).playerUnits.sumOf { unit ->
                (unit.quantity - unit.remainingQuantity) * equipment.require(unit.code).buyCredits
            }
        }.average()
        assertThat(victories).hasSizeGreaterThan(5)
        assertThat(victories.map { it.credits }.average()).isBetween(25.0, 32.0)
        assertThat(outcomes.map { it.credits }.average() + DailyRewardPolicy.BASE_CREDITS / 5.0)
            .isGreaterThanOrEqualTo(averageReplacementCost)
    }

    private fun group(vararg units: UnitBattleSnapshot) = CombatGroupSnapshot(UUID.randomUUID(), 1, 10, units.toList())

    private fun unit(code: String, cp: Int, attack: Int, armor: Int, mobility: Int, recon: Int, support: Int, vararg roles: String) =
        UnitBattleSnapshot(UUID.randomUUID(), code, 1, cp, attack, armor, mobility, recon, support, roles.toSet())
}
