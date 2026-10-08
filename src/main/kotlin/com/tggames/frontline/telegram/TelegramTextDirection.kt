package com.tggames.frontline.telegram

/** Keeps left-to-right technical fragments readable inside Telegram's Arabic right-to-left layout. */
object TelegramTextDirection {
    private const val LTR_ISOLATE = '\u2066'
    private const val POP_DIRECTIONAL_ISOLATE = '\u2069'

    private val arabicLetter = Regex("[\\p{IsArabic}]")
    private val technicalFragment = Regex(
        """(?<![A-Za-z0-9])(?:/?[A-Za-z][A-Za-z0-9._/-]*(?: +[A-Za-z][A-Za-z0-9._/-]*)*|[+-]?\d+(?:[./:\u2013-]\d+)*(?: *[A-Za-z][A-Za-z0-9._/-]*)?)""",
    )

    fun prepare(text: String): String {
        if (!arabicLetter.containsMatchIn(text) || LTR_ISOLATE in text) return text
        return technicalFragment.replace(text) { match ->
            "$LTR_ISOLATE${match.value}$POP_DIRECTIONAL_ISOLATE"
        }
    }

    fun prepare(keyboard: InlineKeyboardMarkup?): InlineKeyboardMarkup? = keyboard?.let { markup ->
        InlineKeyboardMarkup(
            markup.inlineKeyboard.map { row ->
                row.map { button -> button.copy(text = prepare(button.text)) }
            },
        )
    }
}
