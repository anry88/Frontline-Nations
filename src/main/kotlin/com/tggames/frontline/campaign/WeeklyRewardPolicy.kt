package com.tggames.frontline.campaign

import com.tggames.frontline.config.FrontlineProperties

data class WeeklyRewardBreakdown(
    val xp: Int,
    val credits: Int,
    val materials: Int,
    val destroyedPower: Long,
    val capturedObjectives: Int,
    val destructionCredits: Int,
    val captureCredits: Int,
    val destructionXp: Int,
    val captureXp: Int,
)

object WeeklyRewardPolicy {
    fun calculate(
        won: Boolean,
        performance: WeeklyContributionPerformance?,
        config: FrontlineProperties.Campaign,
    ): WeeklyRewardBreakdown {
        val destroyedPower = performance?.destroyedPower ?: 0
        val capturedObjectives = performance?.capturedObjectives ?: 0
        val destructionCredits = (destroyedPower / 100 * config.destroyedCreditsPer100Power).toInt()
        val destructionXp = (destroyedPower / 100 * config.destroyedXpPer100Power).toInt()
        val captureCredits = capturedObjectives * config.captureCredits
        val captureXp = capturedObjectives * config.captureXp
        return WeeklyRewardBreakdown(
            xp = (if (won) config.winnerXp else config.loserXp) + destructionXp + captureXp,
            credits = (if (won) config.winnerCredits else config.loserCredits) + destructionCredits + captureCredits,
            materials = if (won) config.winnerMaterials else config.loserMaterials,
            destroyedPower = destroyedPower,
            capturedObjectives = capturedObjectives,
            destructionCredits = destructionCredits,
            captureCredits = captureCredits,
            destructionXp = destructionXp,
            captureXp = captureXp,
        )
    }
}
