package com.tggames.frontline.game

import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameGuideTest {
    @Test
    fun `every supported language has every guide section`() {
        assertThat(GameGuide.size).isGreaterThanOrEqualTo(5)
        GameLanguage.entries.forEach { language ->
            (0 until GameGuide.size).forEach { page ->
                assertThat(GameGuide.page(language, page).title).isNotBlank()
                assertThat(GameGuide.page(language, page).text).isNotBlank()
            }
        }
    }

    @Test
    fun `weekly schedule is stated in UTC for every language`() {
        GameLanguage.entries.forEach { language ->
            val weeklyPage = GameGuide.page(language, 3)

            assertThat(weeklyPage.text).containsAnyOf("15:00", "15.00").contains("UTC")
        }
    }
}
