package com.tggames.frontline.campaign

import com.tggames.frontline.config.FrontlineProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeeklyRewardPolicyTest {
    private val config = FrontlineProperties.Campaign()

    @Test
    fun `weekly victory base credits are three normal equal-battle wins`() {
        val reward = WeeklyRewardPolicy.calculate(true, null, config)
        assertThat(reward.credits).isEqualTo(90)
        assertThat(reward.xp).isEqualTo(600)
    }

    @Test
    fun `personal destruction and capture contribution adds auditable bonuses`() {
        val reward = WeeklyRewardPolicy.calculate(
            won = true,
            performance = WeeklyContributionPerformance(42, destroyedPower = 700, capturedObjectives = 2),
            config = config,
        )
        assertThat(reward.destructionCredits).isEqualTo(7)
        assertThat(reward.captureCredits).isEqualTo(10)
        assertThat(reward.destructionXp).isEqualTo(350)
        assertThat(reward.captureXp).isEqualTo(200)
        assertThat(reward.credits).isEqualTo(107)
        assertThat(reward.xp).isEqualTo(1_150)
    }
}
