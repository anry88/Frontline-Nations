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
}
