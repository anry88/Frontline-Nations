package com.tggames.frontline.game

import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.ObjectiveResult
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.battle.SpatialEndReason
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.progression.CommanderProgression
import com.tggames.frontline.progression.ForceTierCatalog
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

internal enum class PersonalBattleOutcome { VICTORY, DEFEAT, DRAW }

internal enum class PostBattleAction(val value: String) {
    NEXT_BATTLE("next_battle"),
    RESTORE_GROUP("restore_group"),
    CHOOSE_GROUP("choose_group"),
    ;
}

internal enum class PostBattleReason(val value: String) {
    GROUP_READY("group_ready"),
    GROUP_EMPTY("group_empty"),
    BELOW_MINIMUM("below_minimum"),
    GROUP_RESERVED("group_reserved"),
    ;
}

internal data class PostBattleRecommendation(
    val action: PostBattleAction,
    val reason: PostBattleReason,
    val dailySuggested: Boolean,
)

internal data class NextLevelGoal(
    val level: Int,
    val xpRemaining: Long,
    val nextCapacity: Int,
)

internal enum class BattleInsight {
    ORDERED_OBJECTIVE_HELD,
    ORDERED_OBJECTIVE_ENEMY_HELD,
    ORDERED_OBJECTIVE_PROGRESS,
    ENEMY_DEFEATED_BEFORE_OBJECTIVE,
    PLAYER_DESTROYED_BEFORE_OBJECTIVE,
    PLAYER_WITHDREW_BEHIND,
    OBJECTIVE_NOT_REACHED,
}

internal object BattleResultPresentationPolicy {
    const val SUMMARY_VERSION = 1

    fun recommendNextAction(
        unitCount: Int,
        hasReservedUnits: Boolean,
        usedCp: Int,
        cpLimit: Int,
        minimumBattleCp: Int,
        dailyAvailable: Boolean,
    ): PostBattleRecommendation = when {
        hasReservedUnits -> PostBattleRecommendation(PostBattleAction.CHOOSE_GROUP, PostBattleReason.GROUP_RESERVED, false)
        unitCount == 0 -> PostBattleRecommendation(PostBattleAction.RESTORE_GROUP, PostBattleReason.GROUP_EMPTY, dailyAvailable)
        usedCp < minimumBattleCp || usedCp > cpLimit ->
            PostBattleRecommendation(PostBattleAction.RESTORE_GROUP, PostBattleReason.BELOW_MINIMUM, dailyAvailable)
        else -> PostBattleRecommendation(PostBattleAction.NEXT_BATTLE, PostBattleReason.GROUP_READY, false)
    }

    fun nextLevelGoal(xp: Long): NextLevelGoal {
        val progress = CommanderProgression.progress(xp)
        return NextLevelGoal(
            level = progress.level + 1,
            xpRemaining = progress.requiredForNextLevel - progress.earnedInLevel,
            nextCapacity = ForceTierCatalog.capacityForLevel(progress.level + 1),
        )
    }

    fun insight(
        outcome: PersonalBattleOutcome,
        endReason: SpatialEndReason,
        orderedObjectiveId: String,
        objectives: List<ObjectiveResult>,
        events: List<SpatialBattleEvent>,
    ): BattleInsight {
        val objective = objectives.firstOrNull { it.id == orderedObjectiveId }
        if (objective?.owner == BattleSide.PLAYER) return BattleInsight.ORDERED_OBJECTIVE_HELD
        if (objective?.owner == BattleSide.ENEMY) return BattleInsight.ORDERED_OBJECTIVE_ENEMY_HELD
        if (events.any {
                it.objectiveId == orderedObjectiveId &&
                    it.side == BattleSide.PLAYER &&
                    it.type in setOf(SpatialEventType.CAPTURE_PROGRESS, SpatialEventType.OBJECTIVE_CAPTURED)
            }) return BattleInsight.ORDERED_OBJECTIVE_PROGRESS
        if (outcome == PersonalBattleOutcome.VICTORY) return BattleInsight.ENEMY_DEFEATED_BEFORE_OBJECTIVE
        if (outcome == PersonalBattleOutcome.DEFEAT && endReason == SpatialEndReason.ARMY_DESTROYED) {
            return BattleInsight.PLAYER_DESTROYED_BEFORE_OBJECTIVE
        }
        if (outcome == PersonalBattleOutcome.DEFEAT && endReason == SpatialEndReason.ARMY_ROUTED) {
            return BattleInsight.PLAYER_WITHDREW_BEHIND
        }
        return BattleInsight.OBJECTIVE_NOT_REACHED
    }
}

internal fun markBattleResultDetailsOpened(jdbc: JdbcClient, telegramId: Long, battleId: UUID): Int = jdbc.sql(
    """
    UPDATE battle_next_actions
       SET details_opened_at = COALESCE(details_opened_at, CURRENT_TIMESTAMP),
           details_open_count = details_open_count + 1
     WHERE battle_id = :battleId AND player_telegram_id = :playerId
    """.trimIndent(),
).param("battleId", battleId).param("playerId", telegramId).update()
