package com.tggames.frontline.game

import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramMobileUiContractTest {
    @Test
    fun `critical battle labels fit narrow Telegram keyboards in every locale`() {
        val singleColumnKeys = listOf(
            "first_mission_start",
            "first_mission_configure",
            "result_primary_next_battle",
            "result_primary_restore_group",
            "result_primary_choose_group",
            "other_operations",
            "previous_battle_button",
            "front_groups_button",
            "settings",
            "front_bridge_contribute_button",
            "front_bridge_view_button",
            "front_review_confirm",
            "front_review_prepare_group",
            "front_review_confirm_anyway",
            "front_review_back",
        )
        val twoColumnKeys = listOf(
            "result_details_button",
            "replay_button",
            "replay_retry_button",
            "battle",
            "daily",
            "army",
            "shop",
            "upgrade",
            "profile",
            "ratings_button",
            "guide_button",
        )

        GameLanguage.entries.forEach { language ->
            singleColumnKeys.forEach { key ->
                assertThat(codePoints(GameI18n.t(language, key)))
                    .describedAs("%s single-column label in %s", key, language.code)
                    .isLessThanOrEqualTo(44)
            }
            twoColumnKeys.forEach { key ->
                assertThat(codePoints(GameI18n.t(language, key)))
                    .describedAs("%s two-column label in %s", key, language.code)
                    .isLessThanOrEqualTo(24)
            }
            Tactic.entries.forEach { tactic ->
                assertThat(codePoints("${tactic.icon} ${GameI18n.tactic(language, tactic)}"))
                    .describedAs("%s tactic label in %s", tactic.code, language.code)
                    .isLessThanOrEqualTo(24)
            }
        }
    }

    @Test
    fun `critical first repeat and recovery copy stays within Telegram payload limits`() {
        val messageKeys = listOf(
            "country_title",
            "country_neutral",
            "country_lock_notice",
            "starter_army_ready",
            "first_mission_title",
            "first_mission_goal",
            "first_mission_orders",
            "first_mission_tactic",
            "first_mission_losses",
            "first_mission_no_guarantee",
            "operations",
            "operation_choose",
            "choose_entry",
            "choose_objective",
            "choose_tactic_spatial",
            "battle_complete",
            "replay_queued",
            "replay_already_preparing",
            "replay_ready_resending",
            "replay_retrying",
            "replay_failed_retry",
            "result_reward_summary",
            "result_insight",
            "result_next_level_goal",
            "recovery_title",
            "recovery_inventory_state",
            "recovery_target",
            "recovery_owned_plan",
            "recovery_purchase_plan",
            "recovery_shortage_daily",
            "recovery_shortage_wait",
            "weekly_front_title",
            "front_bridge_title",
            "front_bridge_intro",
            "front_bridge_status_open",
            "front_bridge_status_locked",
            "front_bridge_status_resolved",
            "front_bridge_power",
            "front_bridge_rewards",
            "front_bridge_bonus",
            "front_bridge_no_guarantee",
            "front_bridge_reserve_terms",
            "front_bridge_safe",
            "front_bridge_unsafe",
            "front_review_title",
            "front_review_deadline",
            "front_review_withdrawal",
            "front_review_group_remains",
            "front_review_no_group_remains",
        )

        GameLanguage.entries.forEach { language ->
            messageKeys.forEach { key ->
                val text = GameI18n.t(language, key, "Alpha", "13", "13", "Objective", "00:00")
                assertThat(text)
                    .describedAs("%s copy in %s", key, language.code)
                    .doesNotContain("{0}", "{1}", "{2}", "{3}", "{4}")
                assertThat(codePoints(text))
                    .describedAs("%s copy in %s", key, language.code)
                    .isLessThanOrEqualTo(1024)
            }
        }
    }

    @Test
    fun `all supported locale selectors fit two-column mobile layout`() {
        assertThat(GameLanguage.entries.map { it.code })
            .containsExactly("en", "ru", "es", "pt", "ar", "id", "hi", "tr")
        GameLanguage.entries.forEach { language ->
            assertThat(codePoints("${language.flag} ${language.nativeName} ✅"))
                .describedAs("language selector for %s", language.code)
                .isLessThanOrEqualTo(24)
        }
    }

    private fun codePoints(value: String): Int = value.codePointCount(0, value.length)
}
