package com.tggames.frontline.replay

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.campaign.WeeklyBattleEvent
import com.tggames.frontline.campaign.WeeklyFormationResult
import java.nio.file.Path
import java.util.UUID

enum class ReplayKind(val path: String) {
    PERSONAL("personal"),
    WEEKLY("weekly"),
    ;

    companion object {
        fun fromPath(value: String): ReplayKind? = entries.firstOrNull { it.path == value }
    }
}

data class ReplayArtifact(
    val url: String,
    val width: Int,
    val height: Int,
    val durationSeconds: Int,
)

data class ReplayFile(val path: Path, val contentLength: Long)

data class PersonalReplaySnapshot(
    val battleId: UUID,
    val map: BattleMapDefinition,
    val playerGroup: CombatGroupSnapshot,
    val enemyGroup: CombatGroupSnapshot,
    val playerEntryId: String,
    val enemyEntryId: String,
    val events: List<SpatialBattleEvent>,
)

data class WeeklyReplaySnapshot(
    val matchupId: UUID,
    val map: BattleMapDefinition,
    val formations: List<WeeklyFormationResult>,
    val events: List<WeeklyBattleEvent>,
    val maxTicks: Int,
)
