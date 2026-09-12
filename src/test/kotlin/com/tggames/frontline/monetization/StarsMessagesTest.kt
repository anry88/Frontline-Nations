package com.tggames.frontline.monetization

import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StarsMessagesTest {
    @Test
    fun `menu contains only balance and package prompt while value stays on buttons`() {
        GameLanguage.entries.forEach { language ->
            val menu = StarsMessages.menu(language, 123)
            assertThat(menu).contains("123 Credits")
            assertThat(menu).doesNotContain("20%", "20 %", "twice", "вдвое", "Telegram Stars")
        }

        assertThat(StarsMessages.packButton(GameLanguage.RU, StarsCreditCatalog.packs[1])).contains("% выгоды")
    }
}
