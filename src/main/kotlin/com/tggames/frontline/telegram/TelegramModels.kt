package com.tggames.frontline.telegram

import com.fasterxml.jackson.annotation.JsonProperty

data class TelegramUpdate(
    @param:JsonProperty("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
    @param:JsonProperty("callback_query") val callbackQuery: TelegramCallbackQuery? = null,
)

data class TelegramMessage(
    @param:JsonProperty("message_id") val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val text: String? = null,
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
)

data class TelegramChat(val id: Long)

data class SendMessageRequest(
    @param:JsonProperty("chat_id") val chatId: Long,
    val text: String,
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
