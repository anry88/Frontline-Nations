package com.tggames.frontline.progression

import kotlin.math.sqrt

data class LevelProgress(
    val level: Int,
    val earnedInLevel: Long,
    val requiredForNextLevel: Long,
)

object CommanderProgression {
    const val BASE_LEVEL_XP = 1_000L

    /** Level L -> L + 1 costs BASE_LEVEL_XP * L. */
    fun requiredForNextLevel(level: Int): Long = BASE_LEVEL_XP * level.coerceAtLeast(1)

    /** Total XP at the start of a level: 0, 1,000, 3,000, 6,000, ... */
    fun totalXpForLevel(level: Int): Long {
        val completedLevels = (level.coerceAtLeast(1) - 1).toLong()
        return BASE_LEVEL_XP * completedLevels * (completedLevels + 1) / 2
    }

    fun levelForXp(xp: Long): Int {
        require(xp >= 0) { "XP cannot be negative" }
        var level = ((1.0 + sqrt(1.0 + 8.0 * xp / BASE_LEVEL_XP)) / 2.0).toInt().coerceAtLeast(1)
        while (totalXpForLevel(level + 1) <= xp) level++
        while (totalXpForLevel(level) > xp) level--
        return level
    }

    fun progress(xp: Long): LevelProgress {
        val level = levelForXp(xp)
        return LevelProgress(level, xp - totalXpForLevel(level), requiredForNextLevel(level))
    }
}
