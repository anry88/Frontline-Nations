package com.tggames.frontline.battle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BattleEngineTest {
    private val engine = BattleEngine()
    private val operation = OperationOffer(
        slot = 0,
        battlefield = Battlefield("Дунайская долина", "речная долина"),
        enemy = EnemyArchetype.ARTILLERY,
        difficulty = Difficulty.STANDARD,
    )

    @Test
    fun `same inputs produce exactly the same offers and result`() {
        assertThat(engine.offers("secret", "player:date:5"))
            .isEqualTo(engine.offers("secret", "player:date:5"))

        val first = engine.resolve("secret", "player:battle:5", 3, operation, Tactic.MANEUVER)
        val second = engine.resolve("secret", "player:battle:5", 3, operation, Tactic.MANEUVER)

        assertThat(second).isEqualTo(first)
        assertThat(engine.replay(first.seed, 3, operation, Tactic.MANEUVER)).isEqualTo(first)
    }

    @Test
    fun `operation board always contains three distinct risk reward choices`() {
        val offers = engine.offers("secret", "player:date:4")

        assertThat(offers).hasSize(3)
        assertThat(offers.map { it.difficulty }).containsExactlyInAnyOrderElementsOf(Difficulty.entries)
        assertThat(offers.map { it.battlefield.location }).doesNotHaveDuplicates()
    }

    @Test
    fun `counter tactic materially improves power against artillery`() {
        val counter = engine.resolve("secret", "same-rolls", 2, operation, Tactic.MANEUVER)
        val poorChoice = engine.resolve("secret", "same-rolls", 2, operation, Tactic.DEFENSE)

        assertThat(counter.tacticBonus).isGreaterThan(poorChoice.tacticBonus)
        assertThat(counter.playerPower).isGreaterThan(poorChoice.playerPower)
        assertThat(counter.playerPower - poorChoice.playerPower)
            .isGreaterThanOrEqualTo(counter.tacticBonus - poorChoice.tacticBonus)
    }

    @Test
    fun `battle produces bounded multi-round report and all rewards`() {
        val result = engine.resolve("secret", "another-battle", 1, operation, Tactic.RECON)

        assertThat(result.events).hasSizeBetween(8, 12)
        assertThat(result.playerPower).isPositive()
        assertThat(result.enemyPower).isPositive()
        assertThat(result.xp).isPositive()
        assertThat(result.credits).isPositive()
        assertThat(result.researchPoints).isPositive()
        assertThat(result.materials).isPositive()
        assertThat(result.seedHash).hasSize(64)
    }
}
