package com.tggames.frontline.battle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BattleEngineTest {
    private val engine = BattleEngine()

    @Test
    fun `same inputs produce exactly the same result`() {
        val first = engine.resolve("secret", "player:battle:5", 3)
        val second = engine.resolve("secret", "player:battle:5", 3)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `rewards and power remain inside expected bounds`() {
        val result = engine.resolve("secret", "another-battle", 1)

        assertThat(result.playerPower).isBetween(59, 89)
        assertThat(result.enemyPower).isBetween(61, 91)
        assertThat(result.xp).isPositive()
        assertThat(result.credits).isPositive()
        assertThat(result.materials).isPositive()
        assertThat(result.seedHash).hasSize(64)
    }
}
