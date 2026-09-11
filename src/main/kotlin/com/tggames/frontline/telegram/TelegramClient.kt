package com.tggames.frontline.telegram

import com.tggames.frontline.config.FrontlineProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

class TelegramDeliveryException(
    val playerUnavailable: Boolean,
    operation: String,
    cause: Exception,
) : RuntimeException("Telegram $operation failed", cause)

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
        deliver("sendMessage") {
            restClient.post()
                .uri("/sendMessage")
                .body(SendMessageRequest(chatId, text, keyboard))
                .retrieve()
                .toBodilessEntity()
        }
    }

    fun sendPhoto(chatId: Long, photoUrl: String, caption: String, keyboard: InlineKeyboardMarkup? = null) {
        if (telegramDisabled()) {
            logger.info("Telegram is disabled; photo response for chat {}: {} ({})", chatId, caption, photoUrl)
            return
        }
        deliver("sendPhoto") {
            restClient.post()
                .uri("/sendPhoto")
                .body(SendPhotoRequest(chatId, photoUrl, caption, keyboard))
                .retrieve()
                .toBodilessEntity()
        }
    }

    fun sendAnimation(
        chatId: Long,
        animationUrl: String,
        caption: String,
        width: Int,
        height: Int,
        duration: Int,
        keyboard: InlineKeyboardMarkup? = null,
    ) {
        if (telegramDisabled()) {
            logger.info("Telegram is disabled; animation response for chat {}: {} ({})", chatId, caption, animationUrl)
            return
        }
        deliver("sendAnimation") {
            restClient.post()
                .uri("/sendAnimation")
                .body(SendAnimationRequest(chatId, animationUrl, caption, width, height, duration, replyMarkup = keyboard))
                .retrieve()
                .toBodilessEntity()
        }
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

    private fun deliver(operation: String, request: () -> Unit) {
        try {
            request()
        } catch (error: RestClientResponseException) {
            val unavailable = playerUnavailable(error.statusCode.value(), error.responseBodyAsString)
            throw TelegramDeliveryException(unavailable, operation, error)
        } catch (error: Exception) {
            throw TelegramDeliveryException(false, operation, error)
        }
    }

    companion object {
        private val UNAVAILABLE_DESCRIPTIONS = listOf(
            "bot was blocked",
            "chat not found",
            "user is deactivated",
            "bot can't initiate conversation",
        )

        internal fun playerUnavailable(status: Int, responseBody: String): Boolean {
            val description = responseBody.lowercase()
            return status == 403 || UNAVAILABLE_DESCRIPTIONS.any(description::contains)
        }
    }
}
