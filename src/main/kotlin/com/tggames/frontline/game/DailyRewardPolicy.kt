package com.tggames.frontline.game

import java.time.LocalDate

data class DailyReward(val credits: Long, val streak: Int, val grantsUnit: Boolean)

object DailyRewardPolicy {
    const val BASE_CREDITS = 90L
    const val MAX_CREDITS = 180L
    const val MAX_STREAK = 100

    fun reward(previousStreak: Int, lastClaim: LocalDate?, today: LocalDate): DailyReward {
        require(lastClaim == null || lastClaim < today) { "Daily reward is already claimed" }
        val streak = if (lastClaim == today.minusDays(1)) {
            (previousStreak + 1).coerceAtMost(MAX_STREAK)
        } else {
            1
        }
        return DailyReward(
            credits = BASE_CREDITS +
                ((MAX_CREDITS - BASE_CREDITS) * (streak - 1) + (MAX_STREAK - 1) / 2) / (MAX_STREAK - 1),
            streak = streak,
            grantsUnit = streak == MAX_STREAK,
        )
    }
}
