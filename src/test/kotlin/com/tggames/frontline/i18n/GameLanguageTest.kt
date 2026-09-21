package com.tggames.frontline.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameLanguageTest {
    @Test
    fun `detects supported Telegram language tags and falls back to English`() {
        assertThat(GameLanguage.fromTelegram("pt-BR")).isEqualTo(GameLanguage.PT)
        assertThat(GameLanguage.fromTelegram("ru_RU")).isEqualTo(GameLanguage.RU)
        assertThat(GameLanguage.fromTelegram("zh-hans")).isEqualTo(GameLanguage.EN)
        assertThat(GameLanguage.fromTelegram(null)).isEqualTo(GameLanguage.EN)
    }

    @Test
    fun `spatial battle interface is translated in every supported language`() {
        val keys = listOf(
            "choose_entry", "choose_objective", "choose_tactic_spatial", "operation_choose",
            "objective_control", "end_army_routed", "upgrade", "stat_attack", "stat_armor", "stat_mobility",
            "stat_recon", "stat_support", "stat_map_movement", "stat_weapon_range", "stat_sight", "stat_fire_mode",
            "front_groups_title", "front_groups_help", "front_choose_entry", "front_choose_objective",
            "front_units_reserved_by_groups", "contribution_units_reserved_by_groups", "equipment_assigned_to_group",
            "weekly_front_title", "weekly_battle_time", "previous_result", "previous_battle_button", "weekly_notification_title",
            "new_player_economy_hint", "weekly_victory_bonus_applied", "weekly_victory_bonus_daily_applied",
            "weekly_victory_bonus_profile",
            "weekly_event_objective_captured", "weekly_event_objective_lost", "weekly_event_formation_destroyed",
            "objective_signal_tower", "objective_central_crossing", "objective_supply_depot", "objective_command_post",
            "objective_north_crossing", "objective_south_crossing", "objective_radar", "objective_airfield",
        )

        GameLanguage.entries.forEach { language ->
            keys.forEach { key -> assertThat(GameI18n.t(language, key)).isNotBlank() }
        }
    }

    @Test
    fun `operation copy describes current rules without obsolete daily limit metadata`() {
        assertThat(GameI18n.t(GameLanguage.EN, "operation_choose"))
            .doesNotContainIgnoringCase("daily", "unlimited", "limit")
        assertThat(GameI18n.t(GameLanguage.RU, "operation_choose"))
            .doesNotContainIgnoringCase("суточ", "без лимита", "ограничен")
    }

    @Test
    fun `russian destruction event identifies the attacking side`() {
        assertThat(GameI18n.t(GameLanguage.RU, "event_unit_destroyed", "Ваша сторона", "Самоходная артиллерия"))
            .isEqualTo("Ваша сторона уничтожила технику «Самоходная артиллерия»")
        assertThat(GameI18n.t(GameLanguage.RU, "end_army_routed"))
            .contains("лимит ходов")
    }
}
