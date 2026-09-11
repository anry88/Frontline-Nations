package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DailyRewardPolicyTest {
    private val today = LocalDate.of(2026, 9, 11)

    @Test
    fun `starts at ninety credits and grows toward the compact rounded maximum`() {
        assertThat(DailyRewardPolicy.reward(0, null, today)).isEqualTo(DailyReward(90, 1, false))
        assertThat(DailyRewardPolicy.reward(1, today.minusDays(1), today)).isEqualTo(DailyReward(91, 2, false))
    }

    @Test
    fun `missed day resets streak`() {
        assertThat(DailyRewardPolicy.reward(48, today.minusDays(2), today)).isEqualTo(DailyReward(90, 1, false))
    }

    @Test
    fun `day one hundred and later keep maximum credits and grant equipment`() {
        assertThat(DailyRewardPolicy.reward(99, today.minusDays(1), today)).isEqualTo(DailyReward(180, 100, true))
        assertThat(DailyRewardPolicy.reward(100, today.minusDays(1), today)).isEqualTo(DailyReward(180, 100, true))
    }
}
