package com.tggames.frontline.game

import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.ObjectiveResult
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.battle.SpatialEndReason
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class BattleResultPresentationTest {
    @Test
    fun `ready group is sent to next battle without a daily gate`() {
        val recommendation = BattleResultPresentationPolicy.recommendNextAction(4, false, 10, 10, 10, true)

        assertThat(recommendation).isEqualTo(
            PostBattleRecommendation(PostBattleAction.NEXT_BATTLE, PostBattleReason.GROUP_READY, false),
        )
    }

    @Test
    fun `empty and undersized groups are sent to restoration with optional daily support`() {
        assertThat(BattleResultPresentationPolicy.recommendNextAction(0, false, 0, 10, 10, true))
            .isEqualTo(PostBattleRecommendation(PostBattleAction.RESTORE_GROUP, PostBattleReason.GROUP_EMPTY, true))
        assertThat(BattleResultPresentationPolicy.recommendNextAction(2, false, 5, 10, 10, false))
            .isEqualTo(PostBattleRecommendation(PostBattleAction.RESTORE_GROUP, PostBattleReason.BELOW_MINIMUM, false))
        assertThat(BattleResultPresentationPolicy.recommendNextAction(4, true, 10, 10, 10, true))
            .isEqualTo(PostBattleRecommendation(PostBattleAction.CHOOSE_GROUP, PostBattleReason.GROUP_RESERVED, false))
    }

    @Test
    fun `insight is grounded in objective state for victory defeat and draw`() {
        val held = listOf(ObjectiveResult("ordered", BattleSide.PLAYER, null, 0, 2))
        val enemyHeld = listOf(ObjectiveResult("ordered", BattleSide.ENEMY, null, 0, 2))
        val progress = listOf(
            SpatialBattleEvent(3, SpatialEventType.CAPTURE_PROGRESS, BattleSide.PLAYER, objectiveId = "ordered", amount = 1),
        )

        assertThat(BattleResultPresentationPolicy.insight(PersonalBattleOutcome.VICTORY, SpatialEndReason.ALL_OBJECTIVES_CAPTURED, "ordered", held, emptyList()))
            .isEqualTo(BattleInsight.ORDERED_OBJECTIVE_HELD)
        assertThat(BattleResultPresentationPolicy.insight(PersonalBattleOutcome.DEFEAT, SpatialEndReason.ARMY_DESTROYED, "ordered", enemyHeld, emptyList()))
            .isEqualTo(BattleInsight.ORDERED_OBJECTIVE_ENEMY_HELD)
        assertThat(BattleResultPresentationPolicy.insight(PersonalBattleOutcome.DRAW, SpatialEndReason.ARMY_ROUTED, "ordered", emptyList(), progress))
            .isEqualTo(BattleInsight.ORDERED_OBJECTIVE_PROGRESS)
    }

    @Test
    fun `nearest level goal uses current progression and capacity rules`() {
        assertThat(BattleResultPresentationPolicy.nextLevelGoal(250)).isEqualTo(NextLevelGoal(2, 750, 11))
        assertThat(BattleResultPresentationPolicy.nextLevelGoal(1_000)).isEqualTo(NextLevelGoal(3, 2_000, 12))
    }

    @Test
    fun `post battle result copy exists in all supported languages`() {
        val keys = listOf(
            "result_outcome_victory", "result_outcome_defeat", "result_outcome_draw",
            "result_goal_achieved", "result_goal_not_achieved", "result_reward_summary", "result_insight",
            "result_insight_objective_held", "result_insight_enemy_held", "result_insight_progress",
            "result_insight_enemy_defeated", "result_insight_player_destroyed", "result_insight_player_withdrew",
            "result_insight_not_reached", "result_next_level_goal", "result_daily_available",
            "result_primary_next_battle", "result_primary_restore_group", "result_primary_choose_group",
            "result_details_button", "result_action_unavailable",
        )

        GameLanguage.entries.forEach { language ->
            keys.forEach { key -> assertThat(GameI18n.t(language, key, "A", "B", "C")).isNotBlank() }
        }
    }

    @Test
    fun `repeated details opening keeps first timestamp and increments engagement count`() {
        val jdbc = JdbcClient.create(DriverManagerDataSource("jdbc:h2:mem:result_details;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"))
        val battleId = UUID.randomUUID()
        jdbc.sql(
            """
            CREATE TABLE battle_next_actions(
                battle_id UUID PRIMARY KEY, player_telegram_id BIGINT NOT NULL,
                details_opened_at TIMESTAMP WITH TIME ZONE, details_open_count INTEGER NOT NULL
            )
            """.trimIndent(),
        ).update()
        jdbc.sql("INSERT INTO battle_next_actions VALUES (:battleId, 42, NULL, 0)").param("battleId", battleId).update()

        markBattleResultDetailsOpened(jdbc, 42, battleId)
        val first = jdbc.sql("SELECT details_opened_at FROM battle_next_actions WHERE battle_id = :battleId")
            .param("battleId", battleId).query(java.sql.Timestamp::class.java).single()
        markBattleResultDetailsOpened(jdbc, 42, battleId)
        val state = jdbc.sql("SELECT details_opened_at, details_open_count FROM battle_next_actions WHERE battle_id = :battleId")
            .param("battleId", battleId).query { rs, _ -> rs.getTimestamp("details_opened_at") to rs.getInt("details_open_count") }.single()

        assertThat(state.first).isEqualTo(first)
        assertThat(state.second).isEqualTo(2)
    }
}
