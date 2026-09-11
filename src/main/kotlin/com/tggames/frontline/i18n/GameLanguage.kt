package com.tggames.frontline.i18n

import java.util.Locale

enum class GameLanguage(val code: String, val nativeName: String, val flag: String, val locale: Locale) {
    EN("en", "English", "🇬🇧", Locale.ENGLISH),
    RU("ru", "Русский", "🇷🇺", Locale.forLanguageTag("ru")),
    ES("es", "Español", "🇪🇸", Locale.forLanguageTag("es")),
    PT("pt", "Português", "🇧🇷", Locale.forLanguageTag("pt-BR")),
    AR("ar", "العربية", "🇸🇦", Locale.forLanguageTag("ar")),
    ID("id", "Bahasa Indonesia", "🇮🇩", Locale.forLanguageTag("id")),
    HI("hi", "हिन्दी", "🇮🇳", Locale.forLanguageTag("hi")),
    TR("tr", "Türkçe", "🇹🇷", Locale.forLanguageTag("tr")),
    ;

    companion object {
        fun fromStored(code: String?): GameLanguage = entries.firstOrNull { it.code == code } ?: EN

        fun fromTelegram(languageCode: String?): GameLanguage {
            val primary = languageCode.orEmpty().lowercase().substringBefore('-').substringBefore('_')
            return entries.firstOrNull { it.code == primary } ?: EN
        }
    }
}
