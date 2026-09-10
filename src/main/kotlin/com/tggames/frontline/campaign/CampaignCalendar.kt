package com.tggames.frontline.campaign

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

data class CampaignPeriod(
    val weekKey: String,
    val opensAt: ZonedDateTime,
    val resolvesAt: ZonedDateTime,
)

class CampaignCalendar(private val zoneId: ZoneId) {
    fun periodAt(instant: Instant): CampaignPeriod {
        val localDate = instant.atZone(zoneId).toLocalDate()
        val monday = localDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val fields = WeekFields.ISO
        val weekKey = "%04d-W%02d".format(
            monday.get(fields.weekBasedYear()),
            monday.get(fields.weekOfWeekBasedYear()),
        )
        return CampaignPeriod(
            weekKey = weekKey,
            opensAt = monday.atStartOfDay(zoneId),
            resolvesAt = sunday.atTime(LocalTime.of(15, 0)).atZone(zoneId),
        )
    }
}
