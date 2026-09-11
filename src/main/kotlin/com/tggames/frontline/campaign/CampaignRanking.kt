package com.tggames.frontline.campaign

import com.tggames.frontline.game.AllianceCatalog
import com.tggames.frontline.i18n.GameLanguage
import java.text.Collator

object CampaignRanking {
    fun orderedCodes(ratings: Map<String, Long>): List<String> {
        val english = AllianceCatalog.codes.associateWith { AllianceCatalog.name(it, GameLanguage.EN) }
        val collator = Collator.getInstance(GameLanguage.EN.locale).apply { strength = Collator.PRIMARY }
        return AllianceCatalog.codes.sortedWith { a, b ->
            val ratingOrder = (ratings[b] ?: 0L).compareTo(ratings[a] ?: 0L)
            if (ratingOrder != 0) ratingOrder else collator.compare(english.getValue(a), english.getValue(b))
        }
    }

    fun pairs(ratings: Map<String, Long>): List<Pair<String, String>> =
        orderedCodes(ratings).chunked(2).map { it[0] to it[1] }

    fun updatedRating(previous: Long, battleScore: Long): Long = Math.addExact(previous, battleScore)
}
