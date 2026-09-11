package com.tggames.frontline.monetization

data class StarsCreditPack(
    val id: String,
    val credits: Int,
    val priceStars: Int,
    val bonusPercentVsPrevious: Int,
    val valuePercentVsBase: Int,
)

object StarsCreditCatalog {
    const val CURRENCY = "XTR"

    val packs = listOf(
        StarsCreditPack("credits_100", 100, 20, 0, 100),
        StarsCreditPack("credits_500", 500, 85, 18, 118),
        StarsCreditPack("credits_2500", 2_500, 350, 21, 143),
        StarsCreditPack("credits_5000", 5_000, 600, 17, 167),
        StarsCreditPack("credits_10000", 10_000, 1_000, 20, 200),
    )

    fun find(id: String): StarsCreditPack? = packs.firstOrNull { it.id == id }

    fun payload(telegramId: Long, packId: String): String = "pack=$packId;user=$telegramId"

    fun parsePayload(payload: String): StarsPayload? {
        val parts = payload.split(';').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val packId = parts["pack"] ?: return null
        val telegramId = parts["user"]?.toLongOrNull() ?: return null
        return StarsPayload(packId, telegramId)
    }
}

data class StarsPayload(val packId: String, val telegramId: Long)
