package com.tggames.frontline.campaign

import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeeklyEventLocalizationTest {
    @Test
    fun `weekly objective events use the localized objective name instead of its id`() {
        val event = WeeklyBattleEvent(
            tick = 12,
            type = WeeklyEventType.OBJECTIVE_CAPTURED,
            side = WeeklySide.A,
            objectiveId = "airfield",
            awardedPoints = 700,
        )

        val text = formatWeeklyEvent(
            event,
            allianceA = "RS",
            allianceB = "SC",
            language = GameLanguage.RU,
            objectiveName = { GameI18n.t(GameLanguage.RU, "objective_airfield") },
            unitName = { it },
        )

        assertThat(text).contains("Аэродром").doesNotContain("airfield")
    }

    @Test
    fun `typed weekly events have copy for every supported language`() {
        val event = WeeklyBattleEvent(
            tick = 8,
            type = WeeklyEventType.FORMATION_DESTROYED,
            side = WeeklySide.B,
            formationType = WeeklyFormationType.ARMOR,
        )

        GameLanguage.entries.forEach { language ->
            val text = formatWeeklyEvent(
                event,
                allianceA = "RS",
                allianceB = "SC",
                language = language,
                objectiveName = { it },
                unitName = { it },
            )
            assertThat(text).isNotBlank().doesNotContain("ARMOR")
        }
    }
}
