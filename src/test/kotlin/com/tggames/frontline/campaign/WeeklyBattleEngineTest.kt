package com.tggames.frontline.campaign

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeeklyBattleEngineTest {
    private val engine = WeeklyBattleEngine()
    private val balance = WeeklyBalance(
        npcBasePower = 500,
        npcPerMissingContributor = 150,
        maxNpcCompensation = 900,
        contributionSoftCap = 5_000,
        overflowDivisor = 4,
    )

    @Test
    fun `same weekly inputs produce the same battle and events`() {
        val a = AllianceForce("RS", 1_200, 4)
        val b = AllianceForce("BR", 1_100, 3)

        val first = engine.resolve("secret", "2026-W37", 0, "Дунайская долина", a, b, balance)
        val second = engine.resolve("secret", "2026-W37", 0, "Дунайская долина", a, b, balance)

        assertThat(second).isEqualTo(first)
        assertThat(first.events).hasSize(4)
        assertThat(first.seedHash).hasSize(64)
        assertThat(first.winnerCode).isIn("RS", "BR")
    }

    @Test
    fun `smaller active side receives bounded npc compensation`() {
        val small = AllianceForce("RS", 900, 1)
        val large = AllianceForce("BR", 900, 20)

        assertThat(engine.npcCompensation(small, large, balance)).isEqualTo(900)
        assertThat(engine.npcCompensation(large, small, balance)).isZero()
    }

    @Test
    fun `contribution above soft cap has diminishing returns`() {
        assertThat(engine.cappedContribution(5_000, balance)).isEqualTo(5_000)
        assertThat(engine.cappedContribution(9_000, balance)).isEqualTo(6_000)
    }
}
