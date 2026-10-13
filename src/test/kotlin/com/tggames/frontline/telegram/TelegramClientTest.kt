package com.tggames.frontline.telegram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramClientTest {
    @Test
    fun `blocked deactivated and missing chats are permanently unavailable`() {
        assertThat(TelegramClient.playerUnavailable(403, "Forbidden: bot was blocked by the user")).isTrue()
        assertThat(TelegramClient.playerUnavailable(400, "Bad Request: chat not found")).isTrue()
        assertThat(TelegramClient.playerUnavailable(403, "Forbidden: user is deactivated")).isTrue()
    }

    @Test
    fun `server and media errors remain retryable`() {
        assertThat(TelegramClient.playerUnavailable(500, "Internal Server Error")).isFalse()
        assertThat(TelegramClient.playerUnavailable(400, "Bad Request: failed to get HTTP URL content")).isFalse()
    }

    @Test
    fun `expired or unknown callback queries are stale`() {
        assertThat(TelegramClient.isStaleCallbackError(400, "Bad Request: query is too old and response timeout expired or query ID is invalid")).isTrue()
        assertThat(TelegramClient.isStaleCallbackError(400, "Bad Request: query ID is invalid")).isTrue()
    }

    @Test
    fun `other callback answers are not stale`() {
        assertThat(TelegramClient.isStaleCallbackError(400, "Bad Request: chat not found")).isFalse()
        assertThat(TelegramClient.isStaleCallbackError(500, "Bad Request: query is too old")).isFalse()
        assertThat(TelegramClient.isStaleCallbackError(403, "Forbidden: bot was blocked by the user")).isFalse()
    }
}
