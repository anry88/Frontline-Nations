package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EconomyPolicyTest {
    @Test
    fun `weekly victory multiplier adds twenty percent with integer arithmetic`() {
        assertThat(EconomyPolicy.applyPercent(90, EconomyPolicy.WEEKLY_VICTORY_BONUS_PERCENT)).isEqualTo(108)
        assertThat(EconomyPolicy.applyPercent(30L, EconomyPolicy.WEEKLY_VICTORY_BONUS_PERCENT)).isEqualTo(36L)
    }
}
