package com.tggames.frontline.game

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.Battlefield
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.DeploymentEntry
import com.tggames.frontline.battle.Difficulty
import com.tggames.frontline.battle.EnemyArchetype
import com.tggames.frontline.battle.HexCoord
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.StrategicObjective
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.battle.TerrainType
import com.tggames.frontline.battle.UnitBattleSnapshot
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FirstMissionRecommendationTest {
    @Test
    fun `variant assignment is stable and honors rollout boundaries`() {
        assertThat(FirstMissionRecommendationPolicy.assignedVariant(42, 0)).isEqualTo(OnboardingVariant.LEGACY)
        assertThat(FirstMissionRecommendationPolicy.assignedVariant(42, 100)).isEqualTo(OnboardingVariant.GUIDED_V1)
        assertThat(FirstMissionRecommendationPolicy.assignedVariant(42, 37))
            .isEqualTo(FirstMissionRecommendationPolicy.assignedVariant(42, 37))
    }

    @Test
    fun `new commander receives explainable scouted offer and shortest route`() {
        val risky = OperationOffer(0, Battlefield("Risky", "пустыня"), EnemyArchetype.AIR, Difficulty.RISKY)
        val scouted = OperationOffer(2, Battlefield("Scouted", "лес"), EnemyArchetype.AMBUSH, Difficulty.SCOUTED)
        val recommendation = FirstMissionRecommendationPolicy.recommend(
            offers = listOf(risky, scouted),
            commanderLevel = 1,
            group = reconGroup(),
            mapFor = { map() },
        )

        assertThat(recommendation.version).isEqualTo(1)
        assertThat(recommendation.operation).isEqualTo(scouted)
        assertThat(recommendation.entryId).isEqualTo("entry-a")
        assertThat(recommendation.objectiveId).isEqualTo("objective-1")
        assertThat(recommendation.tactic).isEqualTo(Tactic.RECON)
    }

    @Test
    fun `first mission callback parser rejects stale shapes`() {
        assertThat(parseFirstMissionCallback("first:start:1:17"))
            .isEqualTo(FirstMissionCallback("start", 1, 17))
        assertThat(parseFirstMissionCallback("first:configure:1:18"))
            .isEqualTo(FirstMissionCallback("configure", 1, 18))
        assertThat(parseFirstMissionCallback("first:start:0:17")).isNull()
        assertThat(parseFirstMissionCallback("first:unknown:1:17")).isNull()
        assertThat(parseFirstMissionCallback("first:start:1")).isNull()
    }

    @Test
    fun `guided first mission copy exists in every supported locale`() {
        val keys = listOf(
            "country_lock_notice",
            "first_mission_title",
            "starter_army_ready",
            "first_mission_goal",
            "first_mission_orders",
            "first_mission_tactic",
            "first_mission_losses",
            "first_mission_no_guarantee",
            "first_mission_start",
            "first_mission_configure",
        )

        GameLanguage.entries.forEach { language ->
            keys.forEach { key ->
                assertThat(GameI18n.t(language, key, "A", "B", "C")).isNotBlank()
            }
        }
    }

    @Test
    fun `server readiness rejects empty reserved and invalid groups`() {
        assertThat(firstMissionReadiness(0, false, 0, 10, 10)).isEqualTo(FirstMissionReadiness.EMPTY)
        assertThat(firstMissionReadiness(4, true, 10, 10, 10)).isEqualTo(FirstMissionReadiness.RESERVED)
        assertThat(firstMissionReadiness(2, false, 5, 10, 10)).isEqualTo(FirstMissionReadiness.OUTSIDE_CP_LIMIT)
        assertThat(firstMissionReadiness(5, false, 11, 10, 10)).isEqualTo(FirstMissionReadiness.OUTSIDE_CP_LIMIT)
        assertThat(firstMissionReadiness(4, false, 10, 10, 10)).isEqualTo(FirstMissionReadiness.READY)
    }

    private fun reconGroup() = CombatGroupSnapshot(
        id = UUID.randomUUID(),
        version = 1,
        cpLimit = 10,
        units = listOf(
            UnitBattleSnapshot(
                id = UUID.randomUUID(),
                code = "RECON_VEHICLE",
                level = 1,
                cpCost = 1,
                attack = 8,
                armor = 6,
                mobility = 36,
                recon = 40,
                support = 8,
                roles = setOf("RECON", "MOBILE"),
            ),
        ),
    )

    private fun map() = BattleMapDefinition(
        id = "test-map",
        version = 1,
        nameKey = "map_open_front",
        biomes = listOf("лес"),
        width = 5,
        height = 5,
        baseTerrain = TerrainType.PLAIN,
        cells = emptyList(),
        playerEntries = listOf(
            DeploymentEntry("entry-b", "entry_b", HexCoord(4, 4)),
            DeploymentEntry("entry-a", "entry_a", HexCoord(0, 0)),
        ),
        enemyEntries = emptyList(),
        objectives = listOf(
            StrategicObjective("objective-2", "objective_2", HexCoord(3, 4), 1),
            StrategicObjective("objective-1", "objective_1", HexCoord(1, 0), 1),
        ),
    )
}
