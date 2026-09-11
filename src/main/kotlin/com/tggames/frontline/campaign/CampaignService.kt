package com.tggames.frontline.campaign

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.game.AllianceCatalog
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ContributionOutcome(val accepted: Boolean, val message: String)

data class PendingCampaignNotification(
    val id: Long,
    val playerTelegramId: Long,
    val message: String,
)

@Service
class CampaignService(
    private val jdbc: JdbcClient,
    private val battleEngine: WeeklyBattleEngine,
    private val properties: FrontlineProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val mapCatalog: WeeklyBattleMapCatalog,
) {
    private val calendar = CampaignCalendar(ZoneId.of(properties.gameTimezone))

    @Transactional
    fun ensureCurrentWeek(): CampaignPeriod {
        val period = calendar.periodAt(clock.instant())
        jdbc.sql(
            """
            INSERT INTO campaign_weeks(week_key, scheduled_at, pairing_version)
            VALUES (:week, :scheduledAt, 2)
            ON CONFLICT (week_key) DO NOTHING
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("scheduledAt", Timestamp.from(period.resolvesAt.toInstant()))
            .update()
        ensureMatchups(period)
        return period
    }

    @Transactional
    fun contribute(playerId: Long, allianceCode: String, snapshot: CombatGroupSnapshot, language: GameLanguage = GameLanguage.RU): ContributionOutcome {
        val period = ensureCurrentWeek()
        val week = jdbc.sql(
            "SELECT status, scheduled_at FROM campaign_weeks WHERE week_key = :week FOR UPDATE",
        ).param("week", period.weekKey)
            .query { rs, _ -> rs.getString("status") to rs.getTimestamp("scheduled_at").toInstant() }
            .single()
        if (week.first != "OPEN" || !clock.instant().isBefore(week.second)) {
            return ContributionOutcome(false, campaignText(language, "Contributions are closed: the weekly battle has started. Results will appear in /front.", "Вклады закрыты: недельное сражение уже началось. Итог появится в /front."))
        }

        if (snapshot.units.isEmpty() || snapshot.usedCp <= 0) return ContributionOutcome(false, campaignText(language, "The active group is empty. Add equipment in /army first.", "Активная группа пуста. Сначала добавьте технику в /army."))
        jdbc.sql(
            """
            INSERT INTO campaign_contributions(
                week_key, player_telegram_id, alliance_code, credits, power,
                contribution_type, group_id, group_version, group_snapshot_json
            )
            VALUES (
                :week, :playerId, :alliance, NULL, :power,
                'EQUIPMENT_SNAPSHOT', :groupId, :groupVersion, CAST(:snapshot AS jsonb)
            )
            ON CONFLICT (week_key, player_telegram_id)
                WHERE contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
            DO UPDATE SET alliance_code = EXCLUDED.alliance_code,
                          power = EXCLUDED.power,
                          group_id = EXCLUDED.group_id,
                          group_version = EXCLUDED.group_version,
                          group_snapshot_json = EXCLUDED.group_snapshot_json,
                          created_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("playerId", playerId)
            .param("alliance", allianceCode)
            .param("power", snapshot.usedCp * 100)
            .param("groupId", snapshot.id)
            .param("groupVersion", snapshot.version)
            .param("snapshot", objectMapper.writeValueAsString(snapshot))
            .update()
        return ContributionOutcome(true, campaignText(language, "Active group snapshot added: ${snapshot.usedCp} CP. Your equipment remains in your hangar.", "Снимок активной группы добавлен: ${snapshot.usedCp} CP. Техника остаётся в вашем ангаре."))
    }

    @Transactional
    fun frontText(allianceCode: String?, language: GameLanguage = GameLanguage.RU): String {
        val period = ensureCurrentWeek()
        val matchups = matchupRows(period.weekKey)
        val ownMatchup = allianceCode?.let { code ->
            matchups.firstOrNull { it.allianceA == code || it.allianceB == code }
        }
        val header = buildString {
            appendLine(campaignText(language, "🌍 WEEKLY FRONT ${period.weekKey}", "🌍 НЕДЕЛЬНЫЙ ФРОНТ ${period.weekKey}"))
            appendLine(campaignText(language, "⚔️ Battle: Sunday, ${period.resolvesAt.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))} · Belgrade", "⚔️ Сражение: воскресенье, ${period.resolvesAt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))} · Belgrade"))
            append(scheduleStatus(period, matchups.firstOrNull()?.status, language))
        }
        if (ownMatchup == null) {
            val pairs = matchups.take(10).joinToString("\n") {
                "${AllianceCatalog.option(it.allianceA, language).label} — ${AllianceCatalog.option(it.allianceB, language).label}"
            }
            return "$header\n\n${campaignText(language, "Weekly pairings: ${matchups.size} (first 10 shown)", "Пары недели: ${matchups.size} (показаны первые 10)")}:\n$pairs"
        }
        return if (ownMatchup.status == "RESOLVED") {
            "$header\n\n${resolvedMatchupText(ownMatchup, allianceCode, language)}"
        } else {
            "$header\n\n${openMatchupText(ownMatchup, allianceCode, language)}"
        }
    }

    @Transactional
    fun frontMapPath(allianceCode: String?): String? {
        if (allianceCode == null) return null
        val period = ensureCurrentWeek()
        val mapId = jdbc.sql(
            """
            SELECT COALESCE(map_id, battlefield)
              FROM campaign_matchups
             WHERE week_key = :week AND (alliance_a = :alliance OR alliance_b = :alliance)
             ORDER BY pair_index
             LIMIT 1
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("alliance", allianceCode)
            .query(String::class.java)
            .optional()
            .orElse(null)
        return mapId?.takeIf { mapCatalog.find(it) != null }?.let { "/assets/maps/weekly/$it.png" }
    }

    @Transactional
    fun resolveDueCampaigns(): Int {
        ensureCurrentWeek()
        val due = jdbc.sql(
            "SELECT week_key FROM campaign_weeks WHERE status = 'OPEN' AND scheduled_at <= :now ORDER BY scheduled_at",
        ).param("now", Timestamp.from(clock.instant()))
            .query(String::class.java)
            .list()
        due.forEach(::resolveWeek)
        return due.size
    }

    fun pendingNotifications(limit: Int = 50): List<PendingCampaignNotification> = jdbc.sql(
        """
        SELECT id, player_telegram_id, message
          FROM campaign_notifications
         WHERE sent_at IS NULL AND attempts < 10
         ORDER BY created_at, id
         LIMIT :limit
        """.trimIndent(),
    ).param("limit", limit)
        .query { rs, _ ->
            PendingCampaignNotification(
                rs.getLong("id"),
                rs.getLong("player_telegram_id"),
                rs.getString("message"),
            )
        }.list()

    fun markNotificationSent(id: Long) {
        jdbc.sql(
            "UPDATE campaign_notifications SET sent_at = CURRENT_TIMESTAMP, attempts = attempts + 1, last_error = NULL WHERE id = :id AND sent_at IS NULL",
        ).param("id", id).update()
    }

    fun markNotificationFailed(id: Long, error: String) {
        jdbc.sql(
            "UPDATE campaign_notifications SET attempts = attempts + 1, last_error = :error WHERE id = :id AND sent_at IS NULL",
        ).param("id", id)
            .param("error", error.take(256))
            .update()
    }

    private fun ensureMatchups(period: CampaignPeriod) {
        ensureAllianceRatings()
        val existing = jdbc.sql("SELECT COUNT(*) FROM campaign_matchups WHERE week_key = :week")
            .param("week", period.weekKey).query(Int::class.java).single()
        if (existing > 0) {
            refreshOpenMapSnapshots(period.weekKey)
            return
        }
        val ratings = jdbc.sql("SELECT alliance_code, rating FROM alliance_ratings")
            .query { rs, _ -> rs.getString("alliance_code") to rs.getLong("rating") }.list().toMap()
        CampaignRanking.pairs(ratings).forEachIndexed { pairIndex, pair ->
            val (allianceA, allianceB) = pair
            val matchupId = UUID.nameUUIDFromBytes(
                "${period.weekKey}:v2:$pairIndex:$allianceA:$allianceB".toByteArray(StandardCharsets.UTF_8),
            )
            val map = mapCatalog.maps[Math.floorMod(period.weekKey.hashCode() + pairIndex, mapCatalog.maps.size)]
            jdbc.sql(
                """
                INSERT INTO campaign_matchups(id, week_key, pair_index, battlefield, map_id, map_version, map_snapshot_json, max_ticks, alliance_a, alliance_b)
                VALUES (:id, :week, :pairIndex, :battlefield, :mapId, :mapVersion, CAST(:mapSnapshot AS jsonb), :maxTicks, :allianceA, :allianceB)
                ON CONFLICT (week_key, pair_index) DO NOTHING
                """.trimIndent(),
            ).param("id", matchupId)
                .param("week", period.weekKey)
                .param("pairIndex", pairIndex)
                .param("battlefield", map.id)
                .param("mapId", map.id)
                .param("mapVersion", map.version)
                .param("mapSnapshot", objectMapper.writeValueAsString(map))
                .param("maxTicks", properties.campaign.maxTicks)
                .param("allianceA", allianceA)
                .param("allianceB", allianceB)
                .update()
        }
    }

    private fun refreshOpenMapSnapshots(weekKey: String) {
        mapCatalog.maps.forEach { map ->
            jdbc.sql(
                """
                UPDATE campaign_matchups
                   SET map_version = :mapVersion,
                       map_snapshot_json = CAST(:mapSnapshot AS jsonb),
                       max_ticks = :maxTicks
                 WHERE week_key = :week AND map_id = :mapId AND resolved_at IS NULL
                """.trimIndent(),
            ).param("week", weekKey)
                .param("mapId", map.id)
                .param("mapVersion", map.version)
                .param("mapSnapshot", objectMapper.writeValueAsString(map))
                .param("maxTicks", properties.campaign.maxTicks)
                .update()
        }
    }

    private fun ensureAllianceRatings() {
        val existing = jdbc.sql("SELECT COUNT(*) FROM alliance_ratings").query(Int::class.java).single()
        if (existing == AllianceCatalog.codes.size) return
        AllianceCatalog.codes.forEach { code ->
            jdbc.sql(
                """
                INSERT INTO alliance_ratings(alliance_code, english_name)
                VALUES (:code, :name)
                ON CONFLICT (alliance_code) DO UPDATE SET english_name = EXCLUDED.english_name
                """.trimIndent(),
            ).param("code", code)
                .param("name", AllianceCatalog.name(code, GameLanguage.EN))
                .update()
        }
    }

    private fun resolveWeek(weekKey: String) {
        val status = jdbc.sql("SELECT status FROM campaign_weeks WHERE week_key = :week FOR UPDATE")
            .param("week", weekKey)
            .query(String::class.java)
            .single()
        if (status != "OPEN") return

        matchupRows(weekKey).forEach { matchup ->
            val forceA = force(weekKey, matchup.allianceA)
            val forceB = force(weekKey, matchup.allianceB)
            val map = mapCatalog.require(matchup.mapId ?: matchup.battlefield)
            val result = battleEngine.resolve(
                properties.battleServerSalt,
                weekKey,
                matchup.pairIndex,
                map,
                forceA,
                forceB,
                campaignBalance(matchup.maxTicks),
            )
            val ratingBeforeA = lockRating(matchup.allianceA)
            val ratingBeforeB = lockRating(matchup.allianceB)
            val ratingAfterA = CampaignRanking.updatedRating(ratingBeforeA, result.scoreA)
            val ratingAfterB = CampaignRanking.updatedRating(ratingBeforeB, result.scoreB)
            jdbc.sql(
                """
                UPDATE campaign_matchups
                   SET contribution_a = :contributionA,
                       contribution_b = :contributionB,
                       contributors_a = :contributorsA,
                       contributors_b = :contributorsB,
                       npc_bonus_a = :npcA,
                       npc_bonus_b = :npcB,
                       score_a = :scoreA,
                       score_b = :scoreB,
                       objective_score_a = :objectiveScoreA,
                       objective_score_b = :objectiveScoreB,
                       destroyed_score_a = :destroyedScoreA,
                       destroyed_score_b = :destroyedScoreB,
                       survivor_score_a = :survivorScoreA,
                       survivor_score_b = :survivorScoreB,
                       remaining_power_a = :remainingPowerA,
                       remaining_power_b = :remainingPowerB,
                       completed_ticks = :completedTicks,
                       end_reason = :endReason,
                       rating_before_a = :ratingBeforeA,
                       rating_before_b = :ratingBeforeB,
                       rating_after_a = :ratingAfterA,
                       rating_after_b = :ratingAfterB,
                       winner_code = :winner,
                       battle_seed = :seed,
                       seed_hash = :seedHash,
                       engine_version = 2,
                       events_json = CAST(:events AS jsonb),
                       formations_json = CAST(:formations AS jsonb),
                       objective_state_json = CAST(:objectives AS jsonb),
                       npc_snapshot_a = CAST(:npcAUnits AS jsonb),
                       npc_snapshot_b = CAST(:npcBUnits AS jsonb),
                       resolved_at = CURRENT_TIMESTAMP
                 WHERE id = :id AND resolved_at IS NULL
                """.trimIndent(),
            ).param("contributionA", forceA.contributedPower)
                .param("contributionB", forceB.contributedPower)
                .param("contributorsA", forceA.contributors)
                .param("contributorsB", forceB.contributors)
                .param("npcA", result.npcBonusA)
                .param("npcB", result.npcBonusB)
                .param("scoreA", result.scoreA)
                .param("scoreB", result.scoreB)
                .param("objectiveScoreA", result.objectiveScoreA)
                .param("objectiveScoreB", result.objectiveScoreB)
                .param("destroyedScoreA", result.destroyedScoreA)
                .param("destroyedScoreB", result.destroyedScoreB)
                .param("survivorScoreA", result.survivorScoreA)
                .param("survivorScoreB", result.survivorScoreB)
                .param("remainingPowerA", result.remainingPowerA)
                .param("remainingPowerB", result.remainingPowerB)
                .param("completedTicks", result.completedTicks)
                .param("endReason", result.endReason.name)
                .param("ratingBeforeA", ratingBeforeA)
                .param("ratingBeforeB", ratingBeforeB)
                .param("ratingAfterA", ratingAfterA)
                .param("ratingAfterB", ratingAfterB)
                .param("winner", result.winnerCode)
                .param("seed", result.seed)
                .param("seedHash", result.seedHash)
                .param("events", objectMapper.writeValueAsString(result.events))
                .param("formations", objectMapper.writeValueAsString(result.formations))
                .param("objectives", objectMapper.writeValueAsString(result.objectives))
                .param("npcAUnits", objectMapper.writeValueAsString(result.npcUnitsA))
                .param("npcBUnits", objectMapper.writeValueAsString(result.npcUnitsB))
                .param("id", matchup.id)
                .update()
            updateRating(matchup.allianceA, ratingAfterA, result.scoreA, result.winnerCode == matchup.allianceA)
            updateRating(matchup.allianceB, ratingAfterB, result.scoreB, result.winnerCode == matchup.allianceB)
            issueRewardsAndNotifications(weekKey, matchup, result)
        }
        jdbc.sql(
            "UPDATE campaign_weeks SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE week_key = :week AND status = 'OPEN'",
        ).param("week", weekKey).update()
    }

    private fun lockRating(allianceCode: String): Long = jdbc.sql(
        "SELECT rating FROM alliance_ratings WHERE alliance_code = :code FOR UPDATE",
    ).param("code", allianceCode).query(Long::class.java).single()

    private fun updateRating(allianceCode: String, rating: Long, battleScore: Long, won: Boolean) {
        jdbc.sql(
            """
            UPDATE alliance_ratings
               SET rating = :rating,
                   games_played = games_played + 1,
                   wins = wins + :win,
                   losses = losses + :loss,
                   last_battle_score = :battleScore,
                   updated_at = CURRENT_TIMESTAMP
             WHERE alliance_code = :code
            """.trimIndent(),
        ).param("rating", rating)
            .param("win", if (won) 1 else 0)
            .param("loss", if (won) 0 else 1)
            .param("battleScore", battleScore)
            .param("code", allianceCode)
            .update()
    }

    private fun issueRewardsAndNotifications(
        weekKey: String,
        matchup: MatchupRow,
        result: WeeklyBattleResult,
    ) {
        val participants = jdbc.sql(
            """
            SELECT player_telegram_id, alliance_code, SUM(power) AS power
              FROM campaign_contributions
             WHERE week_key = :week AND alliance_code IN (:allianceA, :allianceB)
               AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
             GROUP BY player_telegram_id, alliance_code
            """.trimIndent(),
        ).param("week", weekKey)
            .param("allianceA", matchup.allianceA)
            .param("allianceB", matchup.allianceB)
            .query { rs, _ ->
                CampaignParticipant(rs.getLong("player_telegram_id"), rs.getString("alliance_code"), rs.getLong("power"))
            }.list()
        val rewardsByPlayer = participants.associate { participant ->
            participant.playerId to issueReward(weekKey, matchup.id, participant, result.winnerCode)
        }
        val players = jdbc.sql(
            "SELECT telegram_id, alliance_code, language FROM players WHERE alliance_code IN (:allianceA, :allianceB)",
        ).param("allianceA", matchup.allianceA)
            .param("allianceB", matchup.allianceB)
            .query { rs, _ -> Triple(rs.getLong("telegram_id"), rs.getString("alliance_code"), GameLanguage.fromStored(rs.getString("language"))) }
            .list()
        players.forEach { (playerId, allianceCode, language) ->
            val message = notificationText(matchup, result, allianceCode, rewardsByPlayer[playerId], language)
            jdbc.sql(
                """
                INSERT INTO campaign_notifications(week_key, player_telegram_id, message)
                VALUES (:week, :playerId, :message)
                ON CONFLICT (week_key, player_telegram_id) DO NOTHING
                """.trimIndent(),
            ).param("week", weekKey)
                .param("playerId", playerId)
                .param("message", message)
                .update()
        }
    }

    private fun issueReward(
        weekKey: String,
        matchupId: UUID,
        participant: CampaignParticipant,
        winnerCode: String,
    ): CampaignReward {
        val won = participant.allianceCode == winnerCode
        val config = properties.campaign
        val reward = CampaignReward(
            id = UUID.nameUUIDFromBytes("$weekKey:${participant.playerId}:reward".toByteArray(StandardCharsets.UTF_8)),
            xp = if (won) config.winnerXp else config.loserXp,
            credits = if (won) config.winnerCredits else config.loserCredits,
            research = if (won) config.winnerResearch else config.loserResearch,
            materials = if (won) config.winnerMaterials else config.loserMaterials,
        )
        val inserted = jdbc.sql(
            """
            INSERT INTO campaign_rewards(
                id, week_key, matchup_id, player_telegram_id, alliance_code, victory,
                contributed_power, xp_reward, credits_reward, research_reward, materials_reward
            )
            VALUES (
                :id, :week, :matchupId, :playerId, :alliance, :victory,
                :power, :xp, :credits, :research, :materials
            )
            ON CONFLICT (week_key, player_telegram_id) DO NOTHING
            """.trimIndent(),
        ).param("id", reward.id)
            .param("week", weekKey)
            .param("matchupId", matchupId)
            .param("playerId", participant.playerId)
            .param("alliance", participant.allianceCode)
            .param("victory", won)
            .param("power", participant.power)
            .param("xp", reward.xp)
            .param("credits", reward.credits)
            .param("research", reward.research)
            .param("materials", reward.materials)
            .update()
        if (inserted == 0) return reward

        jdbc.sql(
            """
            UPDATE players
               SET xp = xp + :xp,
                   credits = credits + :credits,
                   research_points = research_points + :research,
                   materials = materials + :materials,
                   commander_level = LEAST(50, 1 + CAST((xp + :xp) / 1000 AS INTEGER)),
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :playerId
            """.trimIndent(),
        ).param("xp", reward.xp)
            .param("credits", reward.credits)
            .param("research", reward.research)
            .param("materials", reward.materials)
            .param("playerId", participant.playerId)
            .update()
        listOf(
            "XP" to reward.xp,
            "CREDITS" to reward.credits,
            "RESEARCH_POINTS" to reward.research,
            "MATERIALS" to reward.materials,
        ).forEach { (resource, delta) ->
            jdbc.sql(
                """
                INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id)
                VALUES (:playerId, :resource, :delta, 'CAMPAIGN_REWARD', :referenceId)
                """.trimIndent(),
            ).param("playerId", participant.playerId)
                .param("resource", resource)
                .param("delta", delta.toLong())
                .param("referenceId", reward.id)
                .update()
        }
        return reward
    }

    private fun force(weekKey: String, allianceCode: String): AllianceForce = jdbc.sql(
        """
        SELECT power, group_snapshot_json
          FROM campaign_contributions
         WHERE week_key = :week AND alliance_code = :alliance
           AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
        """.trimIndent(),
    ).param("week", weekKey)
        .param("alliance", allianceCode)
        .query { rs, _ -> rs.getLong("power") to rs.getString("group_snapshot_json") }
        .list()
        .let { rows ->
            val snapshots = rows.map { objectMapper.readValue(it.second, CombatGroupSnapshot::class.java) }
            AllianceForce(
                allianceCode,
                snapshots.flatMap { snapshot -> snapshot.units.map { WeeklyUnitContribution(it.code, it.level, it.quantity) } },
                snapshots.size,
                rows.sumOf { it.first },
            )
        }

    private fun matchupRows(weekKey: String): List<MatchupRow> = jdbc.sql(
        """
        SELECT m.id, m.pair_index, m.battlefield, m.map_id, m.max_ticks, m.alliance_a, m.alliance_b,
               m.contribution_a, m.contribution_b, m.npc_bonus_a, m.npc_bonus_b,
               m.score_a, m.score_b, m.objective_score_a, m.objective_score_b,
               m.destroyed_score_a, m.destroyed_score_b, m.survivor_score_a, m.survivor_score_b,
               m.remaining_power_a, m.remaining_power_b, m.completed_ticks, m.end_reason,
               m.rating_before_a, m.rating_before_b, m.rating_after_a, m.rating_after_b,
               m.winner_code, m.events_json, w.status
          FROM campaign_matchups m
          JOIN campaign_weeks w ON w.week_key = m.week_key
         WHERE m.week_key = :week
         ORDER BY m.pair_index
        """.trimIndent(),
    ).param("week", weekKey)
        .query { rs, _ ->
            MatchupRow(
                id = rs.getObject("id", UUID::class.java),
                pairIndex = rs.getInt("pair_index"),
                battlefield = rs.getString("battlefield"),
                mapId = rs.getString("map_id"),
                maxTicks = rs.getInt("max_ticks"),
                allianceA = rs.getString("alliance_a"),
                allianceB = rs.getString("alliance_b"),
                contributionA = rs.getLong("contribution_a"),
                contributionB = rs.getLong("contribution_b"),
                npcBonusA = rs.getInt("npc_bonus_a"),
                npcBonusB = rs.getInt("npc_bonus_b"),
                scoreA = rs.getLong("score_a").takeUnless { rs.wasNull() },
                scoreB = rs.getLong("score_b").takeUnless { rs.wasNull() },
                objectiveScoreA = rs.getLong("objective_score_a"),
                objectiveScoreB = rs.getLong("objective_score_b"),
                destroyedScoreA = rs.getLong("destroyed_score_a"),
                destroyedScoreB = rs.getLong("destroyed_score_b"),
                survivorScoreA = rs.getLong("survivor_score_a"),
                survivorScoreB = rs.getLong("survivor_score_b"),
                remainingPowerA = rs.getLong("remaining_power_a"),
                remainingPowerB = rs.getLong("remaining_power_b"),
                completedTicks = rs.getInt("completed_ticks").takeUnless { rs.wasNull() },
                endReason = rs.getString("end_reason"),
                ratingBeforeA = rs.getLong("rating_before_a").takeUnless { rs.wasNull() },
                ratingBeforeB = rs.getLong("rating_before_b").takeUnless { rs.wasNull() },
                ratingAfterA = rs.getLong("rating_after_a").takeUnless { rs.wasNull() },
                ratingAfterB = rs.getLong("rating_after_b").takeUnless { rs.wasNull() },
                winnerCode = rs.getString("winner_code"),
                eventsJson = rs.getString("events_json"),
                status = rs.getString("status"),
            )
        }.list()

    private fun openMatchupText(matchup: MatchupRow, allianceCode: String, language: GameLanguage): String {
        val ownIsA = matchup.allianceA == allianceCode
        val ownCode = if (ownIsA) matchup.allianceA else matchup.allianceB
        val enemyCode = if (ownIsA) matchup.allianceB else matchup.allianceA
        val ownPower = liveContribution(matchup, ownCode)
        val enemyPower = liveContribution(matchup, enemyCode)
        val signal = when {
            ownPower == 0L && enemyPower == 0L -> campaignText(language, "no advantage detected", "разведка пока не фиксирует перевеса")
            ownPower * 10 < enemyPower * 8 -> campaignText(language, "the opponent is gaining an advantage", "противник наращивает преимущество")
            ownPower * 10 > enemyPower * 12 -> campaignText(language, "your alliance holds the initiative", "ваш альянс удерживает инициативу")
            else -> campaignText(language, "the sides are close", "силы сторон близки")
        }
        return buildString {
            val map = mapCatalog.require(matchup.mapId ?: matchup.battlefield)
            appendLine("🗺 ${GameI18n.t(language, map.nameKey)} · ${map.width}×${map.height}")
            appendLine("${AllianceCatalog.option(ownCode, language).label} vs ${AllianceCatalog.option(enemyCode, language).label}")
            appendLine("5 ${campaignText(language, "capture points", "точек захвата")} · ${matchup.maxTicks} ${campaignText(language, "turn limit", "ходов максимум")}")
            appendLine()
            appendLine(campaignText(language, "Your confirmed power: $ownPower", "Ваш подтверждённый вклад: $ownPower"))
            appendLine("${GameI18n.t(language, "intel")}: $signal.")
            appendLine(campaignText(language, "Every country starts with a random 10–25 CP NPC group.", "Каждая страна начинает со случайной NPC-группой на 10–25 CP."))
            appendLine()
            append(campaignText(language, "Add or refresh your active equipment group: /contribute", "Добавить или обновить активную группу техники: /contribute"))
        }
    }

    private fun resolvedMatchupText(matchup: MatchupRow, allianceCode: String, language: GameLanguage): String {
        val winner = matchup.winnerCode ?: return campaignText(language, "Results are being calculated.", "Результат ещё рассчитывается.")
        val ownWon = allianceCode == winner
        val events: List<WeeklyBattleEvent> = objectMapper.readValue(
            matchup.eventsJson,
            object : TypeReference<List<WeeklyBattleEvent>>() {},
        )
        val highlights = events.takeLast(12).joinToString("\n") { "• ${weeklyEventText(it, matchup, language)}" }
        val map = (matchup.mapId ?: matchup.battlefield).let(mapCatalog::find)
        return buildString {
            appendLine(if (map != null) "🗺 ${GameI18n.t(language, map.nameKey)} · ${map.width}×${map.height}" else "🗺 ${GameI18n.battlefield(language, matchup.battlefield)}")
            appendLine(if (ownWon) campaignText(language, "🏆 YOUR ALLIANCE WON", "🏆 ВАШ АЛЬЯНС ПОБЕДИЛ") else campaignText(language, "🎖 WEEKLY BATTLE COMPLETE", "🎖 НЕДЕЛЬНОЕ СРАЖЕНИЕ ЗАВЕРШЕНО"))
            appendLine("${AllianceCatalog.option(matchup.allianceA, language).label} ${matchup.scoreA} : ${matchup.scoreB} ${AllianceCatalog.option(matchup.allianceB, language).label}")
            appendLine("NPC: +${matchup.npcBonusA} / +${matchup.npcBonusB}")
            appendLine(campaignText(language, "Objectives", "Объекты") + ": ${matchup.objectiveScoreA} / ${matchup.objectiveScoreB}")
            appendLine(campaignText(language, "Enemy force destroyed", "Уничтоженная техника") + ": ${matchup.destroyedScoreA} / ${matchup.destroyedScoreB}")
            appendLine(campaignText(language, "Surviving force", "Уцелевшая техника") + ": ${matchup.survivorScoreA} / ${matchup.survivorScoreB}")
            appendLine(campaignText(language, "Remaining power", "Оставшаяся сила") + ": ${matchup.remainingPowerA} / ${matchup.remainingPowerB}")
            appendLine(campaignText(language, "Rating", "Рейтинг") + ": ${matchup.ratingBeforeA}→${matchup.ratingAfterA} / ${matchup.ratingBeforeB}→${matchup.ratingAfterB}")
            appendLine(campaignText(language, "Duration", "Длительность") + ": ${matchup.completedTicks} / ${matchup.maxTicks}")
            appendLine()
            append(highlights)
        }
    }

    private fun notificationText(
        matchup: MatchupRow,
        result: WeeklyBattleResult,
        allianceCode: String,
        reward: CampaignReward?,
        language: GameLanguage,
    ): String = buildString {
        val won = allianceCode == result.winnerCode
        appendLine(campaignText(language, "🌍 WEEKLY BATTLE COMPLETE", "🌍 НЕДЕЛЬНОЕ СРАЖЕНИЕ ЗАВЕРШЕНО"))
        appendLine(GameI18n.t(language, result.map.nameKey))
        appendLine()
        appendLine(if (won) campaignText(language, "🏆 Your alliance won!", "🏆 Ваш альянс победил!") else campaignText(language, "🎖 Your alliance completed the campaign.", "🎖 Ваш альянс завершил кампанию."))
        appendLine("${AllianceCatalog.option(matchup.allianceA, language).label} ${result.scoreA} : ${result.scoreB} ${AllianceCatalog.option(matchup.allianceB, language).label}")
        if (reward != null) {
            appendLine()
            appendLine(campaignText(language, "Contribution reward:", "Награда за вклад:"))
            appendLine("+${reward.xp} XP · +${reward.credits} Credits")
            append("+${reward.research} Research Points · +${reward.materials} Materials")
        } else {
            appendLine()
            append(campaignText(language, "Rewards go to commanders who contributed before the lock.", "Награда выдаётся командирам, внесшим вклад до блокировки."))
        }
    }

    private fun liveContribution(matchup: MatchupRow, allianceCode: String): Long = jdbc.sql(
        "SELECT COALESCE(SUM(power), 0) FROM campaign_contributions WHERE week_key = (SELECT week_key FROM campaign_matchups WHERE id = :id) AND alliance_code = :alliance AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL",
    ).param("id", matchup.id)
        .param("alliance", allianceCode)
        .query(Long::class.java)
        .single()

    private fun scheduleStatus(period: CampaignPeriod, status: String?, language: GameLanguage): String {
        if (status == "RESOLVED") return campaignText(language, "Status: results published", "Статус: результаты опубликованы")
        val remaining = Duration.between(clock.instant(), period.resolvesAt.toInstant())
        if (remaining.isNegative || remaining.isZero) return campaignText(language, "Status: calculating results", "Статус: идёт расчёт результатов")
        val days = remaining.toDays()
        val hours = remaining.minusDays(days).toHours()
        return campaignText(language, "Until contribution lock: ${days}d ${hours}h", "До блокировки вкладов: ${days}д ${hours}ч")
    }

    private fun replaceCodes(text: String, matchup: MatchupRow, language: GameLanguage): String = text
        .replace(matchup.allianceA, AllianceCatalog.option(matchup.allianceA, language).label)
        .replace(matchup.allianceB, AllianceCatalog.option(matchup.allianceB, language).label)

    private fun weeklyEventText(event: WeeklyBattleEvent, matchup: MatchupRow, language: GameLanguage): String {
        if (event.type == null) return replaceCodes(event.text, matchup, language)
        val side = if (event.side == WeeklySide.A) matchup.allianceA else matchup.allianceB
        val name = AllianceCatalog.option(side, language).label
        return when (event.type) {
            WeeklyEventType.OBJECTIVE_CAPTURED -> campaignText(language, "turn ${event.tick}: $name captured ${event.objectiveId} (+${event.awardedPoints})", "ход ${event.tick}: $name захватывает ${event.objectiveId} (+${event.awardedPoints})")
            WeeklyEventType.OBJECTIVE_LOST -> campaignText(language, "turn ${event.tick}: $name lost ${event.objectiveId}; its points were reset", "ход ${event.tick}: $name теряет ${event.objectiveId}; очки за него обнулены")
            WeeklyEventType.FORMATION_DESTROYED -> campaignText(language, "turn ${event.tick}: $name destroyed ${event.formationType}", "ход ${event.tick}: $name уничтожает соединение ${event.formationType}")
        }
    }

    private fun campaignText(language: GameLanguage, english: String, russian: String): String =
        if (language == GameLanguage.RU) russian else english

    private fun campaignBalance(maxTicks: Int): WeeklyBalance {
        val c = properties.campaign
        return WeeklyBalance(
            maxTicks,
            c.objectiveBasePoints,
            c.objectiveDecayPerTick,
            c.objectiveMinPoints,
            c.survivorScorePercent,
            c.npcMinCp,
            c.npcMaxCp,
        )
    }
}

private data class MatchupRow(
    val id: UUID,
    val pairIndex: Int,
    val battlefield: String,
    val mapId: String?,
    val maxTicks: Int,
    val allianceA: String,
    val allianceB: String,
    val contributionA: Long,
    val contributionB: Long,
    val npcBonusA: Int,
    val npcBonusB: Int,
    val scoreA: Long?,
    val scoreB: Long?,
    val objectiveScoreA: Long,
    val objectiveScoreB: Long,
    val destroyedScoreA: Long,
    val destroyedScoreB: Long,
    val survivorScoreA: Long,
    val survivorScoreB: Long,
    val remainingPowerA: Long,
    val remainingPowerB: Long,
    val completedTicks: Int?,
    val endReason: String?,
    val ratingBeforeA: Long?,
    val ratingBeforeB: Long?,
    val ratingAfterA: Long?,
    val ratingAfterB: Long?,
    val winnerCode: String?,
    val eventsJson: String,
    val status: String,
)

private data class CampaignParticipant(val playerId: Long, val allianceCode: String, val power: Long)

private data class CampaignReward(
    val id: UUID,
    val xp: Int,
    val credits: Int,
    val research: Int,
    val materials: Int,
)
