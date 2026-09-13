package com.tggames.frontline.game

import com.tggames.frontline.campaign.ActiveEconomyBonus
import com.tggames.frontline.battle.BattleResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EconomyPolicyTest {
    @Test
    fun `weekly victory multiplier adds twenty percent with integer arithmetic`() {
        assertThat(EconomyPolicy.applyPercent(90, EconomyPolicy.WEEKLY_VICTORY_BONUS_PERCENT)).isEqualTo(108)
        assertThat(EconomyPolicy.applyPercent(30L, EconomyPolicy.WEEKLY_VICTORY_BONUS_PERCENT)).isEqualTo(36L)
    }

    @Test
    fun `weekly victory bonus uses the same configured multiplier for materials`() {
        val bonus = ActiveEconomyBonus(creditsPercent = 120, xpPercent = 120, endsAt = Instant.MAX)
        val base = BattleResult(
            victory = true,
            playerPower = 100,
            enemyPower = 0,
            compositionPower = 100,
            tacticFit = 0,
            tacticBonus = 0,
            counterBonus = 0,
            terrainBonus = 0,
            xp = 10,
            credits = 20,
            materials = 15,
            seed = 1,
            seedHash = "hash",
            events = emptyList(),
            outcomeCredits = 10,
            destructionCredits = 10,
            outcomeXp = 5,
            destructionXp = 5,
        )

        assertThat(bonus.materialsPercent).isEqualTo(120)
        assertThat(applyWeeklyEconomyBonus(base, bonus)).extracting(
            BattleResult::credits,
            BattleResult::xp,
            BattleResult::materials,
        ).containsExactly(24, 12, 18)
    }
}
