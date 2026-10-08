package com.tggames.frontline.telegram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramTextDirectionTest {
    @Test
    fun `isolates mixed technical fragments in Arabic copy`() {
        val prepared = TelegramTextDirection.prepare(
            "السعة 13/13 CP · المستوى L1 · المكافأة +10 XP و20 Credits · استخدم /battle",
        )

        assertThat(prepared).contains("\u206613/13 CP\u2069")
        assertThat(prepared).contains("\u2066L1\u2069")
        assertThat(prepared).contains("\u2066+10 XP\u2069")
        assertThat(prepared).contains("\u206620 Credits\u2069")
        assertThat(prepared).contains("\u2066/battle\u2069")
    }

    @Test
    fun `leaves non Arabic copy unchanged and Arabic preparation idempotent`() {
        val english = "Capacity 13/13 CP · /battle"
        val arabic = "السعة 13/13 CP"

        assertThat(TelegramTextDirection.prepare(english)).isEqualTo(english)
        assertThat(TelegramTextDirection.prepare(TelegramTextDirection.prepare(arabic)))
            .isEqualTo(TelegramTextDirection.prepare(arabic))
    }

    @Test
    fun `isolates button labels without changing callback data`() {
        val keyboard = InlineKeyboardMarkup(
            listOf(listOf(InlineKeyboardButton("⚔️ المعركة التالية 13 CP", "result:act:fixed"))),
        )

        val prepared = TelegramTextDirection.prepare(keyboard)!!

        assertThat(prepared.inlineKeyboard.single().single().text).contains("\u206613 CP\u2069")
        assertThat(prepared.inlineKeyboard.single().single().callbackData).isEqualTo("result:act:fixed")
    }
}
