package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProfileFormattingTest {
    @Test
    fun `optional profile notices do not shift the main profile block`() {
        val text = formatProfileSections(
            """
                🪖 COMMANDER

                Credits: 100
                Materials: 20
            """,
            "⚡ Weekly bonus",
            "💡 Use /daily and /shop",
        )

        assertThat(text).isEqualTo(
            "🪖 COMMANDER\n\nCredits: 100\nMaterials: 20\n\n⚡ Weekly bonus\n\n💡 Use /daily and /shop",
        )
        assertThat(text.lines()).allMatch { it.isBlank() || !it.startsWith(' ') }
    }
}
