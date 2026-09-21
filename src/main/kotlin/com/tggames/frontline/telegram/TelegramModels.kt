package com.tggames.frontline.telegram

import com.fasterxml.jackson.annotation.JsonProperty

data class TelegramUpdate(
    @param:JsonProperty("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
    @param:JsonProperty("callback_query") val callbackQuery: TelegramCallbackQuery? = null,
    @param:JsonProperty("pre_checkout_query") val preCheckoutQuery: TelegramPreCheckoutQuery? = null,
)

data class TelegramMessage(
    @param:JsonProperty("message_id") val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null,
    @param:JsonProperty("successful_payment") val successfulPayment: TelegramSuccessfulPayment? = null,
)

data class TelegramPreCheckoutQuery(
    val id: String,
    val from: TelegramUser,
    val currency: String,
    @param:JsonProperty("total_amount") val totalAmount: Int,
    @param:JsonProperty("invoice_payload") val invoicePayload: String,
)

data class TelegramSuccessfulPayment(
    val currency: String,
    @param:JsonProperty("total_amount") val totalAmount: Int,
    @param:JsonProperty("invoice_payload") val invoicePayload: String,
    @param:JsonProperty("telegram_payment_charge_id") val telegramPaymentChargeId: String,
    @param:JsonProperty("provider_payment_charge_id") val providerPaymentChargeId: String? = null,
)

data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val message: TelegramMessage? = null,
    val data: String? = null,
)

data class TelegramUser(
    val id: Long,
    @param:JsonProperty("first_name") val firstName: String,
    val username: String? = null,
    @param:JsonProperty("language_code") val languageCode: String? = null,
)

data class TelegramChat(
    val id: Long,
    val type: String? = null,
) {
    /** Telegram sends `private`, `group`, `supergroup` or `channel`. Old payloads/tests may omit it. */
    fun isGroupChat(): Boolean = when (type?.lowercase()) {
        "group", "supergroup" -> true
        else -> if (type == null) id < 0 else false
    }
}

data class SendMessageRequest(
    @param:JsonProperty("chat_id") val chatId: Long,
    val text: String,
    @param:JsonProperty("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

data class SendPhotoRequest(
    @param:JsonProperty("chat_id") val chatId: Long,
    val photo: String,
    val caption: String,
    @param:JsonProperty("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

data class SendAnimationRequest(
    @param:JsonProperty("chat_id") val chatId: Long,
    val animation: String,
    val caption: String,
    val width: Int,
    val height: Int,
    val duration: Int,
    @param:JsonProperty("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

data class InlineKeyboardMarkup(
    @param:JsonProperty("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

data class InlineKeyboardButton(
    val text: String,
    @param:JsonProperty("callback_data") val callbackData: String,
)

data class AnswerCallbackRequest(
    @param:JsonProperty("callback_query_id") val callbackQueryId: String,
)

data class LabeledPrice(
    val label: String,
    val amount: Int,
)

data class SendInvoiceRequest(
    @param:JsonProperty("chat_id") val chatId: Long,
    val title: String,
    val description: String,
    val payload: String,
    @param:JsonProperty("provider_token") val providerToken: String = "",
    val currency: String = "XTR",
    val prices: List<LabeledPrice>,
)

data class AnswerPreCheckoutRequest(
    @param:JsonProperty("pre_checkout_query_id") val preCheckoutQueryId: String,
    val ok: Boolean,
    @param:JsonProperty("error_message") val errorMessage: String? = null,
)

data class RefundStarPaymentRequest(
    @param:JsonProperty("user_id") val userId: Long,
    @param:JsonProperty("telegram_payment_charge_id") val telegramPaymentChargeId: String,
)
