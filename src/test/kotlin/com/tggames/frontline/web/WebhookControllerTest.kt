package com.tggames.frontline.web

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.game.GameService
import com.tggames.frontline.telegram.TelegramUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class WebhookControllerTest {
    private val gameService = mock(GameService::class.java)
    private val controller = WebhookController(
        gameService,
        FrontlineProperties(telegram = FrontlineProperties.Telegram(webhookSecret = "expected-secret")),
    )

    @Test
    fun `accepts an update with the configured secret`() {
        val update = TelegramUpdate(updateId = 42)

        controller.webhook("expected-secret", update)

        verify(gameService).handle(update)
    }

    @Test
    fun `rejects an update without the configured secret`() {
        val error = assertThrows<ResponseStatusException> {
            controller.webhook(null, TelegramUpdate(updateId = 43))
        }

        assertThat(error.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
