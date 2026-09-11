package com.tggames.frontline.telegram

import com.tggames.frontline.config.FrontlineProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class TelegramClient(
    private val restClient: RestClient,
    private val properties: FrontlineProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendMessage(chatId: Long, text: String, keyboard: InlineKeyboardMarkup? = null) {
        if (telegramDisabled()) {
            logger.info("Telegram is disabled; response for chat {}: {}", chatId, text)
            return
        }
        restClient.post()
            .uri("/sendMessage")
            .body(SendMessageRequest(chatId, text, keyboard))
            .retrieve()
            .toBodilessEntity()
    }

    fun sendPhoto(chatId: Long, photoUrl: String, caption: String, keyboard: InlineKeyboardMarkup? = null) {
        if (telegramDisabled()) {
            logger.info("Telegram is disabled; photo response for chat {}: {} ({})", chatId, caption, photoUrl)
            return
        }
        restClient.post()
            .uri("/sendPhoto")
            .body(SendPhotoRequest(chatId, photoUrl, caption, keyboard))
            .retrieve()
            .toBodilessEntity()
    }

    fun answerCallback(callbackId: String) {
        if (telegramDisabled()) return
        restClient.post()
            .uri("/answerCallbackQuery")
            .body(AnswerCallbackRequest(callbackId))
            .retrieve()
            .toBodilessEntity()
    }

    private fun telegramDisabled() = properties.telegram.botToken.isBlank() || properties.telegram.botToken == "disabled"
}
