package com.tggames.frontline.progression

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommanderProgressionTest {
    @Test
    fun `each next commander level costs one thousand more xp`() {
        assertThat((1..5).map(CommanderProgression::requiredForNextLevel)).containsExactly(
            1_000L, 2_000L, 3_000L, 4_000L, 5_000L,
        )
        assertThat((1..5).map(CommanderProgression::totalXpForLevel)).containsExactly(
            0L, 1_000L, 3_000L, 6_000L, 10_000L,
        )
    }

    @Test
    fun `level and visible progress are derived from cumulative xp boundaries`() {
        assertThat(CommanderProgression.levelForXp(999)).isEqualTo(1)
        assertThat(CommanderProgression.levelForXp(1_000)).isEqualTo(2)
        assertThat(CommanderProgression.levelForXp(2_999)).isEqualTo(2)
        assertThat(CommanderProgression.levelForXp(3_000)).isEqualTo(3)
        assertThat(CommanderProgression.progress(3_750)).isEqualTo(LevelProgress(3, 750, 3_000))
    }
}
