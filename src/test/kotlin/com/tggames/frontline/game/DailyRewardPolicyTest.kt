package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DailyRewardPolicyTest {
    private val today = LocalDate.of(2026, 9, 11)

    @Test
    fun `starts at three starter-group replacement costs and grows by one percent`() {
        assertThat(DailyRewardPolicy.reward(0, null, today)).isEqualTo(DailyReward(9_450, 1, false))
        assertThat(DailyRewardPolicy.reward(1, today.minusDays(1), today)).isEqualTo(DailyReward(9_545, 2, false))
    }

    @Test
    fun `missed day resets streak`() {
        assertThat(DailyRewardPolicy.reward(48, today.minusDays(2), today)).isEqualTo(DailyReward(9_450, 1, false))
    }

    @Test
    fun `day one hundred and later keep maximum credits and grant equipment`() {
        assertThat(DailyRewardPolicy.reward(99, today.minusDays(1), today)).isEqualTo(DailyReward(18_855, 100, true))
        assertThat(DailyRewardPolicy.reward(100, today.minusDays(1), today)).isEqualTo(DailyReward(18_855, 100, true))
    }
}
