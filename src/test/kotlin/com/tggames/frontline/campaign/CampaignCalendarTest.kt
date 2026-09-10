package com.tggames.frontline.campaign

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class CampaignCalendarTest {
    private val calendar = CampaignCalendar(ZoneId.of("Europe/Belgrade"))

    @Test
    fun `summer campaign resolves Sunday at 15 Belgrade time`() {
        val period = calendar.periodAt(Instant.parse("2026-09-10T12:00:00Z"))

        assertThat(period.weekKey).isEqualTo("2026-W37")
        assertThat(period.resolvesAt.dayOfWeek.name).isEqualTo("SUNDAY")
        assertThat(period.resolvesAt.hour).isEqualTo(15)
        assertThat(period.resolvesAt.toInstant()).isEqualTo(Instant.parse("2026-09-13T13:00:00Z"))
    }

    @Test
    fun `winter campaign keeps local time across daylight saving change`() {
        val period = calendar.periodAt(Instant.parse("2026-12-08T12:00:00Z"))

        assertThat(period.resolvesAt.hour).isEqualTo(15)
        assertThat(period.resolvesAt.toInstant()).isEqualTo(Instant.parse("2026-12-13T14:00:00Z"))
    }
}
