package com.tggames.frontline.campaign

import com.tggames.frontline.game.AllianceCatalog
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.text.Collator

class CampaignRankingTest {
    @Test
    fun `first round pairs all countries in English alphabetical order`() {
        val ordered = CampaignRanking.orderedCodes(emptyMap())
        assertThat(ordered).hasSize(250)
        val names = ordered.map { AllianceCatalog.name(it, GameLanguage.EN) }
        val collator = Collator.getInstance(GameLanguage.EN.locale).apply { strength = Collator.PRIMARY }
        assertThat(names.zipWithNext().all { (a, b) -> collator.compare(a, b) <= 0 }).isTrue()
        assertThat(CampaignRanking.pairs(emptyMap())).hasSize(125)
    }

    @Test
    fun `winner adds its battle score`() {
        val ordered = CampaignRanking.orderedCodes(mapOf("RS" to 8_000, "BR" to 12_000))
        assertThat(ordered.indexOf("BR")).isLessThan(ordered.indexOf("RS"))
        assertThat(CampaignRanking.updatedRating(8_000, 900, 850, true, 2, 15)).isEqualTo(8_900)
    }

    @Test
    fun `close defeat removes only the configured minimum`() {
        assertThat(CampaignRanking.updatedRating(10_000, 5_000, 5_000, false, 2, 15)).isEqualTo(9_800)
    }

    @Test
    fun `defeat penalty grows with the score margin`() {
        val closeDefeat = CampaignRanking.updatedRating(10_000, 4_500, 5_000, false, 2, 15)
        val rout = CampaignRanking.updatedRating(10_000, 0, 5_000, false, 2, 15)

        assertThat(closeDefeat).isEqualTo(9_670)
        assertThat(rout).isEqualTo(8_500)
    }

    @Test
    fun `loser keeps rating history and never gains rating`() {
        assertThat(CampaignRanking.updatedRating(10_000, 4_500, 5_000, false, 2, 15)).isLessThan(10_000)
        assertThat(CampaignRanking.updatedRating(0, 4_500, 5_000, false, 2, 15)).isZero()
        assertThat(CampaignRanking.updatedRating(1, 0, 5_000, false, 2, 15)).isEqualTo(1)
    }
}
