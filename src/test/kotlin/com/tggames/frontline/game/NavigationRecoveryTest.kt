package com.tggames.frontline.game

import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NavigationRecoveryTest {
    @Test
    fun `recovery actions follow player stage and stay within three choices`() {
        val country = NavigationRecoveryPolicy.decide(false, false, 0, false, true)
        val nickname = NavigationRecoveryPolicy.decide(true, true, 0, false, true)
        val first = NavigationRecoveryPolicy.decide(true, false, 0, true, true)
        val repeat = NavigationRecoveryPolicy.decide(true, false, 2, true, true)
        val restoreWithDaily = NavigationRecoveryPolicy.decide(true, false, 1, false, true)
        val restoreWithoutDaily = NavigationRecoveryPolicy.decide(true, false, 1, false, false)

        assertThat(country.primary).isEqualTo(NavigationAction.CHOOSE_COUNTRY)
        assertThat(nickname.actions).containsExactly(NavigationAction.CONFIRM_NICKNAME, NavigationAction.CANCEL_NICKNAME, NavigationAction.HELP)
        assertThat(first.primary).isEqualTo(NavigationAction.FIRST_OPERATION)
        assertThat(repeat.primary).isEqualTo(NavigationAction.NEXT_BATTLE)
        assertThat(restoreWithDaily.actions).containsExactly(NavigationAction.RESTORE_ARMY, NavigationAction.DAILY, NavigationAction.HELP)
        assertThat(restoreWithoutDaily.actions).containsExactly(NavigationAction.RESTORE_ARMY, NavigationAction.HELP)
        assertThat(listOf(country, nickname, first, repeat, restoreWithDaily, restoreWithoutDaily)).allSatisfy {
            assertThat(it.actions).hasSizeBetween(1, 3)
        }
    }

    @Test
    fun `navigation recovery copy exists in all supported locales`() {
        val keys = buildList {
            NavigationErrorCategory.entries.forEach { add("navigation_${it.value}") }
            NavigationStage.entries.forEach { add("navigation_stage_${it.value}") }
            NavigationAction.entries.forEach { add("navigation_action_${it.value}") }
        }
        GameLanguage.entries.forEach { language ->
            keys.forEach { key -> assertThat(GameI18n.t(language, key, "stage")).isNotBlank() }
        }
    }
}
