package com.tggames.frontline.game

object AllianceCatalog {
    val all = linkedMapOf(
        "RS" to "🇷🇸 Сербия",
        "BR" to "🇧🇷 Бразилия",
        "IN" to "🇮🇳 Индия",
        "JP" to "🇯🇵 Япония",
        "EG" to "🇪🇬 Египет",
        "CA" to "🇨🇦 Канада",
        "XK" to "🇽🇰 Косово",
        "PS" to "🇵🇸 Палестина",
    )

    fun name(code: String): String = all[code] ?: code
}
