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
    fun `later rounds rank by cumulative points without wiping loser history`() {
        val ordered = CampaignRanking.orderedCodes(mapOf("RS" to 8_000, "BR" to 12_000))
        assertThat(ordered.indexOf("BR")).isLessThan(ordered.indexOf("RS"))
        assertThat(CampaignRanking.updatedRating(8_000, 900)).isEqualTo(8_900)
    }
}
