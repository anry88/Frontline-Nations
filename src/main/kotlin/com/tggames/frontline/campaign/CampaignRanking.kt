package com.tggames.frontline.campaign

import com.tggames.frontline.game.AllianceCatalog
import com.tggames.frontline.i18n.GameLanguage
import java.math.BigInteger
import java.text.Collator

object CampaignRanking {
    const val CURRENT_RATING_VERSION = 2

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

    fun updatedRating(
        previous: Long,
        ownBattleScore: Long,
        opponentBattleScore: Long,
        won: Boolean,
        minimumLossPercent: Int,
        maximumLossPercent: Int,
    ): Long {
        require(previous >= 0)
        require(ownBattleScore >= 0)
        require(opponentBattleScore >= 0)
        require(minimumLossPercent in 1..99)
        require(maximumLossPercent in minimumLossPercent..99)
        if (won) return Math.addExact(previous, ownBattleScore)
        if (previous == 0L) return 0

        val leadingScore = maxOf(ownBattleScore, opponentBattleScore, 1L)
        val defeatMargin = (opponentBattleScore - ownBattleScore).coerceAtLeast(0)
        val severityBasisPoints = BigInteger.valueOf(defeatMargin)
            .multiply(TEN_THOUSAND)
            .divide(BigInteger.valueOf(leadingScore))
            .longValueExact()
        val lossRange = maximumLossPercent - minimumLossPercent
        val lossBasisPoints = minimumLossPercent * 100L + lossRange * severityBasisPoints / 100
        val loss = maxOf(
            1L,
            BigInteger.valueOf(previous)
                .multiply(BigInteger.valueOf(lossBasisPoints))
                .add(NINE_THOUSAND_NINE_HUNDRED_NINETY_NINE)
                .divide(TEN_THOUSAND)
                .longValueExact(),
        )
        return maxOf(1L, previous - loss)
    }

    private val TEN_THOUSAND = BigInteger.valueOf(10_000)
    private val NINE_THOUSAND_NINE_HUNDRED_NINETY_NINE = BigInteger.valueOf(9_999)
}
