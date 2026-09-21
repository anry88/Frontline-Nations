package com.tggames.frontline.game

import com.tggames.frontline.game.GameService.Companion.GROUP_PLAYER_COMMANDS
import com.tggames.frontline.telegram.TelegramChat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GroupMessageFilterTest {
    @Test
    fun `plain text is not a command`() {
        assertThat(parseBotCommand("hello everyone")).isNull()
        assertThat(parseBotCommand("")).isNull()
        assertThat(parseBotCommand("   ")).isNull()
    }

    @Test
    fun `slash command parses name mention and argument`() {
        assertThat(parseBotCommand("/start")).isEqualTo(ParsedBotCommand("/start", null, ""))
        assertThat(parseBotCommand("/start hello")).isEqualTo(ParsedBotCommand("/start", null, "hello"))
        assertThat(parseBotCommand("/START@frontline_nations_bot")).isEqualTo(
            ParsedBotCommand("/start", "frontline_nations_bot", ""),
        )
        assertThat(parseBotCommand("/battle@frontline_nations_bot deep raid")).isEqualTo(
            ParsedBotCommand("/battle", "frontline_nations_bot", "deep raid"),
        )
        assertThat(parseBotCommand("/help@other_bot")).isEqualTo(
            ParsedBotCommand("/help", "other_bot", ""),
        )
    }

    @Test
    fun `group chat detection uses type with id fallback`() {
        assertThat(TelegramChat(123, "private").isGroupChat()).isFalse()
        assertThat(TelegramChat(-5, "group").isGroupChat()).isTrue()
        assertThat(TelegramChat(-5, "supergroup").isGroupChat()).isTrue()
        assertThat(TelegramChat(-5, "channel").isGroupChat()).isFalse()
        assertThat(TelegramChat(-100, null).isGroupChat()).isTrue()
        assertThat(TelegramChat(100, null).isGroupChat()).isFalse()
    }

    @Test
    fun `group filter keeps player commands and drops foreign mentions`() {
        val botUsername = "frontline_nations_bot"
        fun shouldHandle(chat: TelegramChat, text: String): Boolean {
            val parsed = parseBotCommand(text) ?: return false
            if (parsed.mention != null && !parsed.mention.equals(botUsername, ignoreCase = true)) return false
            return parsed.command in GROUP_PLAYER_COMMANDS
        }

        val group = TelegramChat(-1, "supergroup")
        assertThat(shouldHandle(group, "hello")).isFalse()
        assertThat(shouldHandle(group, "/battle")).isTrue()
        assertThat(shouldHandle(group, "/battle@frontline_nations_bot")).isTrue()
        assertThat(shouldHandle(group, "/battle@other_bot")).isFalse()
        assertThat(shouldHandle(group, "/unknowncmd")).isFalse()
    }
}
