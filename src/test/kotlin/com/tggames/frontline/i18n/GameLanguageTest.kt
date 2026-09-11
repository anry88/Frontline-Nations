package com.tggames.frontline.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameLanguageTest {
    @Test
    fun `detects supported Telegram language tags and falls back to English`() {
        assertThat(GameLanguage.fromTelegram("pt-BR")).isEqualTo(GameLanguage.PT)
        assertThat(GameLanguage.fromTelegram("ru_RU")).isEqualTo(GameLanguage.RU)
        assertThat(GameLanguage.fromTelegram("zh-hans")).isEqualTo(GameLanguage.EN)
        assertThat(GameLanguage.fromTelegram(null)).isEqualTo(GameLanguage.EN)
    }
}
