package com.tggames.frontline.campaign

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class CampaignCalendarTest {
    private val calendar = CampaignCalendar(ZoneId.of("UTC"))

    @Test
    fun `campaign resolves Sunday at 15 UTC`() {
        val period = calendar.periodAt(Instant.parse("2026-09-10T12:00:00Z"))

        assertThat(period.weekKey).isEqualTo("2026-W37")
        assertThat(period.resolvesAt.dayOfWeek.name).isEqualTo("SUNDAY")
        assertThat(period.resolvesAt.hour).isEqualTo(15)
        assertThat(period.resolvesAt.toInstant()).isEqualTo(Instant.parse("2026-09-13T15:00:00Z"))
    }

    @Test
    fun `campaign keeps the same UTC time year round`() {
        val period = calendar.periodAt(Instant.parse("2026-12-08T12:00:00Z"))

        assertThat(period.resolvesAt.hour).isEqualTo(15)
        assertThat(period.resolvesAt.toInstant()).isEqualTo(Instant.parse("2026-12-13T15:00:00Z"))
    }

    @Test
    fun `next contribution period starts immediately after current campaign resolves`() {
        val current = calendar.periodAt(Instant.parse("2026-09-13T16:00:00Z"))
        val next = calendar.nextPeriod(current)

        assertThat(current.weekKey).isEqualTo("2026-W37")
        assertThat(next.weekKey).isEqualTo("2026-W38")
        assertThat(next.opensAt.toInstant()).isEqualTo(Instant.parse("2026-09-14T00:00:00Z"))
        assertThat(next.resolvesAt.toInstant()).isEqualTo(Instant.parse("2026-09-20T15:00:00Z"))
    }
}
