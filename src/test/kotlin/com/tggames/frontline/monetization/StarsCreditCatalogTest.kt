package com.tggames.frontline.monetization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StarsCreditCatalogTest {
    @Test
    fun `pack ladder doubles value by the largest tier`() {
        assertThat(StarsCreditCatalog.packs.map { it.credits to it.priceStars }).containsExactly(
            100 to 20,
            500 to 85,
            2_500 to 350,
            5_000 to 600,
            10_000 to 1_000,
        )

        val creditsPerStar = StarsCreditCatalog.packs.map { it.credits.toDouble() / it.priceStars }
        assertThat(creditsPerStar.zipWithNext { previous, next -> next / previous })
            .allSatisfy { step -> assertThat(step).isBetween(1.16, 1.22) }
        assertThat(creditsPerStar.last() / creditsPerStar.first()).isEqualTo(2.0)
    }

    @Test
    fun `invoice payload binds a known pack to its player`() {
        val payload = StarsCreditCatalog.payload(42L, "credits_500")

        assertThat(StarsCreditCatalog.parsePayload(payload)).isEqualTo(StarsPayload("credits_500", 42L))
        assertThat(StarsCreditCatalog.parsePayload("pack=credits_500;user=other")).isNull()
        assertThat(StarsCreditCatalog.parsePayload("pack=credits_500")).isNull()
    }
}
