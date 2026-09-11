package com.tggames.frontline.campaign

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.DeploymentEntry
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.game.AllianceCatalog
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.inventory.InventoryService
import com.tggames.frontline.progression.CommanderProgression
import com.tggames.frontline.progression.ForceTierCatalog
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

data class FrontContribution(
    val id: Long,
    val presetNo: Int,
    val groupName: String,
    val snapshot: CombatGroupSnapshot,
    val entryId: String?,
    val tactic: Tactic,
)

data class FrontDeployment(val entries: List<DeploymentEntry>)

data class PendingCampaignNotification(
    val id: Long,
    val playerTelegramId: Long,
    val message: String,
    val matchupId: UUID,
    val allianceCode: String,
    val language: GameLanguage,
    val messageSent: Boolean,
    val mediaSent: Boolean,
)

data class ActiveEconomyBonus(
    val creditsPercent: Int,
    val xpPercent: Int,
    val endsAt: Instant,
)

@Service
class CampaignService(
    private val jdbc: JdbcClient,
    private val battleEngine: WeeklyBattleEngine,
    private val properties: FrontlineProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val mapCatalog: WeeklyBattleMapCatalog,
    private val inventory: InventoryService,
    private val equipment: EquipmentCatalog,
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
    fun contribute(
        playerId: Long,
        allianceCode: String,
        presetNo: Int,
        snapshot: CombatGroupSnapshot,
        unitIds: List<UUID>,
        entryId: String,
        tactic: Tactic,
        language: GameLanguage = GameLanguage.RU,
    ): ContributionOutcome {
        val period = ensureCurrentWeek()
        val week = jdbc.sql(
            "SELECT status, scheduled_at FROM campaign_weeks WHERE week_key = :week FOR UPDATE",
        ).param("week", period.weekKey)
            .query { rs, _ -> rs.getString("status") to rs.getTimestamp("scheduled_at").toInstant() }
            .single()
        if (week.first != "OPEN" || !clock.instant().isBefore(week.second)) {
            return ContributionOutcome(false, campaignText(language, "Contributions are closed: the weekly battle has started. Results will appear in /front.", "Вклады закрыты: недельное сражение уже началось. Итог появится в /front."))
        }

        if (snapshot.units.isEmpty() || snapshot.usedCp <= 0) return ContributionOutcome(false, campaignText(language, "This group is empty. Add equipment in /army first.", "Эта группа пуста. Сначала добавьте технику в /army."))
        if (presetNo !in 1..3) return ContributionOutcome(false, campaignText(language, "This group is no longer available.", "Эта группа больше недоступна."))
        val deployment = deploymentFor(period.weekKey, allianceCode)
            ?: return ContributionOutcome(false, campaignText(language, "No weekly battle was found for your country.", "Для вашей страны не найден недельный бой."))
        if (deployment.entries.none { it.id == entryId }) {
            return ContributionOutcome(false, campaignText(language, "This entry is not available to your side.", "Эта точка входа недоступна вашей стороне."))
        }

        val previous = jdbc.sql(
            """
            SELECT id, unit_ids
              FROM campaign_contributions
             WHERE week_key = :week AND player_telegram_id = :playerId AND preset_no = :preset
               AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
             FOR UPDATE
            """.trimIndent(),
        ).param("week", period.weekKey).param("playerId", playerId).param("preset", presetNo)
            .query { rs, _ -> rs.getLong("id") to uuidArray(rs.getArray("unit_ids").array) }
            .optional().orElse(null)
        if (!inventory.replaceWeeklyReservation(playerId, period.weekKey, previous?.second.orEmpty(), unitIds)) {
            return ContributionOutcome(false, campaignText(language, "Some units are destroyed or already committed to another weekly battle.", "Часть техники уничтожена или уже закреплена за другим недельным боем."))
        }
        previous?.let { (id, _) ->
            jdbc.sql("UPDATE campaign_contributions SET voided_at = CURRENT_TIMESTAMP WHERE id = :id AND voided_at IS NULL")
                .param("id", id).update()
        }
        jdbc.sql(
            """
            INSERT INTO campaign_contributions(
                week_key, player_telegram_id, alliance_code, credits, power,
                contribution_type, group_id, group_version, group_snapshot_json,
                preset_no, deployment_entry, tactic, unit_ids
            )
            VALUES (
                :week, :playerId, :alliance, NULL, :power,
                'EQUIPMENT_SNAPSHOT', :groupId, :groupVersion, CAST(:snapshot AS jsonb),
                :preset, :entry, :tactic, ARRAY[:unitIds]::uuid[]
            )
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("playerId", playerId)
            .param("alliance", allianceCode)
            .param("power", snapshot.usedCp * 100)
            .param("groupId", snapshot.id)
            .param("groupVersion", snapshot.version)
            .param("snapshot", objectMapper.writeValueAsString(snapshot))
            .param("preset", presetNo)
            .param("entry", entryId)
            .param("tactic", tactic.code)
            .param("unitIds", unitIds)
            .update()
        return ContributionOutcome(true, campaignText(language, "Group ${presetNo} was sent to the front.", "Группа ${presetNo} отправлена на фронт."))
    }

    @Transactional
    fun withdraw(playerId: Long, contributionId: Long, language: GameLanguage = GameLanguage.RU): ContributionOutcome {
        val period = ensureCurrentWeek()
        val week = jdbc.sql("SELECT status, scheduled_at FROM campaign_weeks WHERE week_key = :week FOR UPDATE")
            .param("week", period.weekKey)
            .query { rs, _ -> rs.getString("status") to rs.getTimestamp("scheduled_at").toInstant() }
            .single()
        if (week.first != "OPEN" || !clock.instant().isBefore(week.second)) {
            return ContributionOutcome(false, campaignText(language, "The battle has started; groups can no longer be withdrawn.", "Бой уже начался; отряды больше нельзя отозвать."))
        }
        val contribution = jdbc.sql(
            "SELECT unit_ids FROM campaign_contributions WHERE id = :id AND week_key = :week AND player_telegram_id = :player AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL FOR UPDATE",
        ).param("id", contributionId).param("week", period.weekKey).param("player", playerId)
            .query { rs, _ -> uuidArray(rs.getArray("unit_ids").array) }.optional().orElse(null)
            ?: return ContributionOutcome(false, campaignText(language, "This group is no longer on the front.", "Этого отряда уже нет на фронте."))
        inventory.releaseWeeklyReservation(playerId, period.weekKey, contribution)
        jdbc.sql("UPDATE campaign_contributions SET voided_at = CURRENT_TIMESTAMP WHERE id = :id AND voided_at IS NULL")
            .param("id", contributionId).update()
        return ContributionOutcome(true, campaignText(language, "The group returned to the arsenal.", "Отряд возвращён в арсенал."))
    }

    @Transactional
    fun frontDeployment(allianceCode: String): FrontDeployment? {
        val period = ensureCurrentWeek()
        return deploymentFor(period.weekKey, allianceCode)
    }

    @Transactional
    fun contributions(playerId: Long): List<FrontContribution> {
        val period = ensureCurrentWeek()
        return contributions(period.weekKey, playerId)
    }

    @Transactional
    fun frontText(playerId: Long?, allianceCode: String?, language: GameLanguage = GameLanguage.RU): String {
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
            "$header\n\n${openMatchupText(ownMatchup, playerId, allianceCode, language)}"
        }
    }

    @Transactional
    fun frontMapPath(allianceCode: String?): String? {
        if (allianceCode == null) return null
        val period = ensureCurrentWeek()
        val map = jdbc.sql(
            """
            SELECT COALESCE(map_id, battlefield) AS map_id, COALESCE(map_version, 1) AS map_version
              FROM campaign_matchups
             WHERE week_key = :week AND (alliance_a = :alliance OR alliance_b = :alliance)
             ORDER BY pair_index
             LIMIT 1
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("alliance", allianceCode)
            .query { rs, _ -> rs.getString("map_id") to rs.getInt("map_version") }
            .optional()
            .orElse(null)
        return map?.takeIf { mapCatalog.find(it.first) != null }
            ?.let { "/assets/maps/weekly/${it.first}.png?v=${it.second}" }
    }

    @Transactional
    fun frontReplayId(allianceCode: String?): UUID? {
        if (allianceCode == null) return null
        val period = ensureCurrentWeek()
        return jdbc.sql(
            """
            SELECT id
              FROM campaign_matchups
             WHERE week_key = :week
               AND resolved_at IS NOT NULL
               AND (alliance_a = :alliance OR alliance_b = :alliance)
             ORDER BY pair_index
             LIMIT 1
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("alliance", allianceCode)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
    }

    fun activeEconomyBonus(allianceCode: String?): ActiveEconomyBonus? {
        if (allianceCode == null) return null
        return jdbc.sql(
            """
            SELECT credits_percent, xp_percent, ends_at
              FROM alliance_economy_bonuses
             WHERE alliance_code = :alliance AND starts_at <= :now AND ends_at > :now
            """.trimIndent(),
        ).param("alliance", allianceCode)
            .param("now", Timestamp.from(clock.instant()))
            .query { rs, _ ->
                ActiveEconomyBonus(rs.getInt("credits_percent"), rs.getInt("xp_percent"), rs.getTimestamp("ends_at").toInstant())
            }.optional().orElse(null)
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

    fun nextPendingNotification(afterId: Long = 0): PendingCampaignNotification? = jdbc.sql(
        """
        SELECT notification.id, notification.player_telegram_id, notification.message,
               notification.matchup_id, notification.alliance_code, player.language,
               notification.message_sent_at IS NOT NULL AS message_sent,
               notification.media_sent_at IS NOT NULL AS media_sent
          FROM campaign_notifications notification
          JOIN players player ON player.telegram_id = notification.player_telegram_id
         WHERE notification.sent_at IS NULL
           AND notification.abandoned_at IS NULL
           AND notification.attempts < 10
           AND player.telegram_unavailable_at IS NULL
           AND notification.id > :afterId
         ORDER BY notification.id
         LIMIT 1
        """.trimIndent(),
    ).param("afterId", afterId).query { rs, _ ->
            PendingCampaignNotification(
                rs.getLong("id"),
                rs.getLong("player_telegram_id"),
                rs.getString("message"),
                rs.getObject("matchup_id", UUID::class.java),
                rs.getString("alliance_code"),
                GameLanguage.fromStored(rs.getString("language")),
                rs.getBoolean("message_sent"),
                rs.getBoolean("media_sent"),
            )
        }.optional().orElse(null)

    fun markNotificationMessageSent(id: Long) {
        jdbc.sql(
            """
            UPDATE campaign_notifications
               SET message_sent_at = COALESCE(message_sent_at, CURRENT_TIMESTAMP),
                   sent_at = CASE WHEN media_sent_at IS NOT NULL THEN CURRENT_TIMESTAMP ELSE sent_at END,
                   last_error = NULL
             WHERE id = :id AND sent_at IS NULL AND abandoned_at IS NULL
            """.trimIndent(),
        ).param("id", id).update()
    }

    fun markNotificationMediaSent(id: Long) {
        jdbc.sql(
            """
            UPDATE campaign_notifications
               SET media_sent_at = COALESCE(media_sent_at, CURRENT_TIMESTAMP),
                   sent_at = CASE WHEN message_sent_at IS NOT NULL THEN CURRENT_TIMESTAMP ELSE sent_at END,
                   last_error = NULL
             WHERE id = :id AND sent_at IS NULL AND abandoned_at IS NULL
            """.trimIndent(),
        ).param("id", id).update()
    }

    fun markNotificationFailed(id: Long, error: String) {
        jdbc.sql(
            "UPDATE campaign_notifications SET attempts = attempts + 1, last_error = :error WHERE id = :id AND sent_at IS NULL",
        ).param("id", id)
            .param("error", error.take(256))
            .update()
    }

    @Transactional
    fun markPlayerUnavailable(playerId: Long, reason: String) {
        jdbc.sql(
            """
            UPDATE players
               SET telegram_unavailable_at = COALESCE(telegram_unavailable_at, CURRENT_TIMESTAMP),
                   telegram_unavailable_reason = :reason,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :player
            """.trimIndent(),
        ).param("player", playerId).param("reason", reason.take(64)).update()
        jdbc.sql(
            """
            UPDATE campaign_notifications
               SET abandoned_at = CURRENT_TIMESTAMP,
                   last_error = :reason
             WHERE player_telegram_id = :player AND sent_at IS NULL AND abandoned_at IS NULL
            """.trimIndent(),
        ).param("player", playerId).param("reason", reason.take(256)).update()
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
                       engine_version = 6,
                       events_json = CAST(:events AS jsonb),
                       formations_json = CAST(:formations AS jsonb),
                       objective_state_json = CAST(:objectives AS jsonb),
                       contribution_performance_json = CAST(:contributionPerformance AS jsonb),
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
                .param("contributionPerformance", objectMapper.writeValueAsString(result.contributionPerformance))
                .param("npcAUnits", objectMapper.writeValueAsString(result.npcUnitsA))
                .param("npcBUnits", objectMapper.writeValueAsString(result.npcUnitsB))
                .param("id", matchup.id)
                .update()
            updateRating(matchup.allianceA, ratingAfterA, result.scoreA, result.winnerCode == matchup.allianceA)
            updateRating(matchup.allianceB, ratingAfterB, result.scoreB, result.winnerCode == matchup.allianceB)
            val casualties = inventory.settleWeeklyCasualties(
                weekKey, matchup.id, matchup.allianceA,
                result.formations.filter { it.side == WeeklySide.A }, result.npcUnitsA, result.seed,
            ) + inventory.settleWeeklyCasualties(
                weekKey, matchup.id, matchup.allianceB,
                result.formations.filter { it.side == WeeklySide.B }, result.npcUnitsB, result.seed,
            )
            casualties.forEach { outcome ->
                jdbc.sql(
                    "UPDATE campaign_contributions SET units_survived = :survived, units_lost = :lost WHERE id = :id AND week_key = :week AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL",
                ).param("survived", outcome.survived).param("lost", outcome.lost)
                    .param("id", outcome.contributionId).param("week", weekKey).update()
            }
            issueRewardsAndNotifications(weekKey, matchup, result)
            activateWinnerBonus(weekKey, result.winnerCode, matchup.allianceA, matchup.allianceB)
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
            SELECT player_telegram_id, alliance_code, SUM(power) AS power,
                   SUM(units_survived) AS units_survived, SUM(units_lost) AS units_lost
              FROM campaign_contributions
             WHERE week_key = :week AND alliance_code IN (:allianceA, :allianceB)
               AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
             GROUP BY player_telegram_id, alliance_code
            """.trimIndent(),
        ).param("week", weekKey)
            .param("allianceA", matchup.allianceA)
            .param("allianceB", matchup.allianceB)
            .query { rs, _ ->
                CampaignParticipant(
                    rs.getLong("player_telegram_id"), rs.getString("alliance_code"), rs.getLong("power"),
                    rs.getInt("units_survived"), rs.getInt("units_lost"),
                )
            }.list()
        val performanceByPlayer = result.contributionPerformance.associateBy { it.playerId }
        val rewardsByPlayer = participants.associate { participant ->
            participant.playerId to issueReward(
                weekKey,
                matchup.id,
                participant,
                result.winnerCode,
                performanceByPlayer[participant.playerId],
            )
        }
        val players = jdbc.sql(
            "SELECT telegram_id, alliance_code, language FROM players WHERE alliance_code IN (:allianceA, :allianceB) AND telegram_unavailable_at IS NULL",
        ).param("allianceA", matchup.allianceA)
            .param("allianceB", matchup.allianceB)
            .query { rs, _ -> Triple(rs.getLong("telegram_id"), rs.getString("alliance_code"), GameLanguage.fromStored(rs.getString("language"))) }
            .list()
        players.forEach { (playerId, allianceCode, language) ->
            val message = notificationText(matchup, result, allianceCode, rewardsByPlayer[playerId], language)
            jdbc.sql(
                """
                INSERT INTO campaign_notifications(week_key, player_telegram_id, message, matchup_id, alliance_code)
                VALUES (:week, :playerId, :message, :matchupId, :alliance)
                ON CONFLICT (week_key, player_telegram_id) DO NOTHING
                """.trimIndent(),
            ).param("week", weekKey)
                .param("playerId", playerId)
                .param("message", message)
                .param("matchupId", matchup.id)
                .param("alliance", allianceCode)
                .update()
        }
    }

    private fun issueReward(
        weekKey: String,
        matchupId: UUID,
        participant: CampaignParticipant,
        winnerCode: String,
        performance: WeeklyContributionPerformance?,
    ): CampaignReward {
        val won = participant.allianceCode == winnerCode
        val config = properties.campaign
        val breakdown = WeeklyRewardPolicy.calculate(won, performance, config)
        val reward = CampaignReward(
            id = UUID.nameUUIDFromBytes("$weekKey:${participant.playerId}:reward".toByteArray(StandardCharsets.UTF_8)),
            xp = breakdown.xp,
            credits = breakdown.credits,
            materials = breakdown.materials,
            survived = participant.survived,
            lost = participant.lost,
            destroyedPower = breakdown.destroyedPower,
            capturedObjectives = breakdown.capturedObjectives,
            destructionCredits = breakdown.destructionCredits,
            captureCredits = breakdown.captureCredits,
            destructionXp = breakdown.destructionXp,
            captureXp = breakdown.captureXp,
        )
        val inserted = jdbc.sql(
            """
            INSERT INTO campaign_rewards(
                id, week_key, matchup_id, player_telegram_id, alliance_code, victory,
                contributed_power, xp_reward, credits_reward, research_reward, materials_reward,
                destroyed_power, captured_objectives, destruction_credits, capture_credits,
                destruction_xp, capture_xp
            )
            VALUES (
                :id, :week, :matchupId, :playerId, :alliance, :victory,
                :power, :xp, :credits, 0, :materials,
                :destroyedPower, :capturedObjectives, :destructionCredits, :captureCredits,
                :destructionXp, :captureXp
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
            .param("materials", reward.materials)
            .param("destroyedPower", reward.destroyedPower)
            .param("capturedObjectives", reward.capturedObjectives)
            .param("destructionCredits", reward.destructionCredits)
            .param("captureCredits", reward.captureCredits)
            .param("destructionXp", reward.destructionXp)
            .param("captureXp", reward.captureXp)
            .update()
        if (inserted == 0) return reward

        val xpBefore = jdbc.sql("SELECT xp FROM players WHERE telegram_id = :playerId FOR UPDATE")
            .param("playerId", participant.playerId).query(Long::class.java).single()
        val xpAfter = xpBefore + reward.xp
        val levelAfter = CommanderProgression.levelForXp(xpAfter)
        jdbc.sql(
            """
            UPDATE players
                   SET xp = xp + :xp,
                   credits = credits + :credits,
                   materials = materials + :materials,
                   commander_level = :commanderLevel,
                   command_capacity = :commandCapacity,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :playerId
            """.trimIndent(),
        ).param("xp", reward.xp)
            .param("credits", reward.credits)
            .param("materials", reward.materials)
            .param("commanderLevel", levelAfter)
            .param("commandCapacity", ForceTierCatalog.capacityForLevel(levelAfter))
            .param("playerId", participant.playerId)
            .update()
        listOf(
            "XP" to reward.xp,
            "CREDITS" to reward.credits,
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

    private fun activateWinnerBonus(weekKey: String, allianceCode: String, allianceA: String, allianceB: String) {
        val startsAt = jdbc.sql("SELECT scheduled_at FROM campaign_weeks WHERE week_key = :week")
            .param("week", weekKey).query { rs, _ -> rs.getTimestamp("scheduled_at").toInstant() }.single()
        val endsAt = startsAt.plus(Duration.ofDays(properties.campaign.victoryBonusDays))
        jdbc.sql("DELETE FROM alliance_economy_bonuses WHERE alliance_code IN (:allianceA, :allianceB)")
            .param("allianceA", allianceA).param("allianceB", allianceB).update()
        jdbc.sql(
            """
            INSERT INTO alliance_economy_bonuses(
                alliance_code, source_week_key, starts_at, ends_at, credits_percent, xp_percent
            ) VALUES (
                :alliance, :week, :startsAt, :endsAt, :creditsPercent, :xpPercent
            )
            ON CONFLICT (alliance_code) DO UPDATE
               SET source_week_key = EXCLUDED.source_week_key,
                   starts_at = EXCLUDED.starts_at,
                   ends_at = EXCLUDED.ends_at,
                   credits_percent = EXCLUDED.credits_percent,
                   xp_percent = EXCLUDED.xp_percent,
                   updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("alliance", allianceCode)
            .param("week", weekKey)
            .param("startsAt", Timestamp.from(startsAt))
            .param("endsAt", Timestamp.from(endsAt))
            .param("creditsPercent", properties.campaign.victoryBonusPercent)
            .param("xpPercent", properties.campaign.victoryBonusPercent)
            .update()
    }

    private fun deploymentFor(weekKey: String, allianceCode: String): FrontDeployment? {
        val row = jdbc.sql(
            """
            SELECT map_id, battlefield, alliance_a, alliance_b
              FROM campaign_matchups
             WHERE week_key = :week AND (alliance_a = :alliance OR alliance_b = :alliance)
             ORDER BY pair_index
             LIMIT 1
            """.trimIndent(),
        ).param("week", weekKey).param("alliance", allianceCode)
            .query { rs, _ -> DeploymentRow(rs.getString("map_id") ?: rs.getString("battlefield"), rs.getString("alliance_a"), rs.getString("alliance_b")) }
            .optional().orElse(null) ?: return null
        val map = mapCatalog.require(row.mapId)
        return FrontDeployment(if (row.allianceA == allianceCode) map.playerEntries else map.enemyEntries)
    }

    private fun contributions(weekKey: String, playerId: Long): List<FrontContribution> = jdbc.sql(
        """
        SELECT contribution.id, contribution.preset_no, COALESCE(battle_group.name, 'Group ' || contribution.preset_no) AS group_name,
               contribution.group_snapshot_json, contribution.deployment_entry, contribution.tactic
          FROM campaign_contributions contribution
          LEFT JOIN battle_groups battle_group ON battle_group.id = contribution.group_id
         WHERE contribution.week_key = :week AND contribution.player_telegram_id = :player
           AND contribution.contribution_type = 'EQUIPMENT_SNAPSHOT' AND contribution.voided_at IS NULL
         ORDER BY contribution.preset_no
        """.trimIndent(),
    ).param("week", weekKey).param("player", playerId)
        .query { rs, _ ->
            FrontContribution(
                id = rs.getLong("id"),
                presetNo = rs.getInt("preset_no"),
                groupName = rs.getString("group_name"),
                snapshot = objectMapper.readValue(rs.getString("group_snapshot_json"), CombatGroupSnapshot::class.java),
                entryId = rs.getString("deployment_entry"),
                tactic = Tactic.fromCode(rs.getString("tactic") ?: "maneuver") ?: Tactic.MANEUVER,
            )
        }.list()

    private fun matchupWeek(matchupId: UUID): String = jdbc.sql("SELECT week_key FROM campaign_matchups WHERE id = :id")
        .param("id", matchupId).query(String::class.java).single()

    private fun uuidArray(value: Any): List<UUID> = when (value) {
        is Array<*> -> value.mapNotNull { it as? UUID }
        else -> emptyList()
    }

    private fun force(weekKey: String, allianceCode: String): AllianceForce = jdbc.sql(
        """
        SELECT id, player_telegram_id, power, group_snapshot_json, deployment_entry, tactic
          FROM campaign_contributions
         WHERE week_key = :week AND alliance_code = :alliance
           AND contribution_type = 'EQUIPMENT_SNAPSHOT' AND voided_at IS NULL
        """.trimIndent(),
    ).param("week", weekKey)
        .param("alliance", allianceCode)
        .query { rs, _ ->
            ContributionSnapshotRow(
                rs.getLong("id"),
                rs.getLong("player_telegram_id"),
                rs.getLong("power"),
                rs.getString("group_snapshot_json"),
                rs.getString("deployment_entry"),
                Tactic.fromCode(rs.getString("tactic") ?: "maneuver") ?: Tactic.MANEUVER,
            )
        }
        .list()
        .let { rows ->
            AllianceForce(
                allianceCode,
                rows.flatMap { row ->
                    objectMapper.readValue(row.snapshotJson, CombatGroupSnapshot::class.java).units.map {
                        WeeklyUnitContribution(it.code, it.level, it.quantity, row.playerId, row.id, row.entryId, row.tactic)
                    }
                },
                rows.size,
                rows.sumOf { it.power },
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

    private fun openMatchupText(matchup: MatchupRow, playerId: Long?, allianceCode: String, language: GameLanguage): String {
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
            appendLine("🗺 ${GameI18n.t(language, map.nameKey)}")
            appendLine("${AllianceCatalog.option(ownCode, language).label} vs ${AllianceCatalog.option(enemyCode, language).label}")
            appendLine("5 ${campaignText(language, "capture points", "точек захвата")} · ${matchup.maxTicks} ${campaignText(language, "turn limit", "ходов максимум")}")
            appendLine()
            val groups = playerId?.let { contributions(matchupWeek(matchup.id), it) }.orEmpty()
            appendLine(campaignText(language, "Your groups on the front:", "Ваши отряды на фронте:"))
            if (groups.isEmpty()) appendLine(campaignText(language, "— none", "— пока нет"))
            groups.forEach { contribution ->
                val composition = contribution.snapshot.units.joinToString(", ") {
                    val definition = equipment.require(it.code)
                    "${it.quantity}× ${definition.emoji} ${definition.name(language)} L${it.level}"
                }
                appendLine("— ${contribution.groupName}: $composition · ${contribution.entryId ?: "—"} · ${GameI18n.tactic(language, contribution.tactic)}")
            }
            appendLine("${GameI18n.t(language, "intel")}: $signal.")
            appendLine()
            append(campaignText(language, "Manage deployed groups: /contribute", "Управление отправленными отрядами: /contribute"))
        }
    }

    private fun resolvedMatchupText(matchup: MatchupRow, allianceCode: String, language: GameLanguage): String {
        val winner = matchup.winnerCode ?: return campaignText(language, "Results are being calculated.", "Результат ещё рассчитывается.")
        val ownWon = allianceCode == winner
        val events: List<WeeklyBattleEvent> = objectMapper.readValue(
            matchup.eventsJson,
            object : TypeReference<List<WeeklyBattleEvent>>() {},
        )
        val highlights = events.filter {
            it.type == null || it.type in setOf(
                WeeklyEventType.OBJECTIVE_CAPTURED,
                WeeklyEventType.OBJECTIVE_LOST,
                WeeklyEventType.FORMATION_DESTROYED,
            )
        }.takeLast(12).joinToString("\n") { "• ${weeklyEventText(it, matchup, language)}" }
        val map = (matchup.mapId ?: matchup.battlefield).let(mapCatalog::find)
        return buildString {
            appendLine(if (map != null) "🗺 ${GameI18n.t(language, map.nameKey)}" else "🗺 ${GameI18n.battlefield(language, matchup.battlefield)}")
            appendLine(if (ownWon) campaignText(language, "🏆 YOUR ALLIANCE WON", "🏆 ВАШ АЛЬЯНС ПОБЕДИЛ") else campaignText(language, "🎖 WEEKLY BATTLE COMPLETE", "🎖 НЕДЕЛЬНОЕ СРАЖЕНИЕ ЗАВЕРШЕНО"))
            appendLine("${AllianceCatalog.option(matchup.allianceA, language).label} ${matchup.scoreA} : ${matchup.scoreB} ${AllianceCatalog.option(matchup.allianceB, language).label}")
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
            appendLine("+${reward.materials} Materials")
            if (reward.destroyedPower > 0 || reward.capturedObjectives > 0) {
                appendLine(
                    campaignText(
                        language,
                        "Personal action: ${reward.destroyedPower} enemy power destroyed, ${reward.capturedObjectives} objectives captured (+${reward.destructionCredits + reward.captureCredits} Credits, +${reward.destructionXp + reward.captureXp} XP)",
                        "Личный вклад: уничтожено ${reward.destroyedPower} силы противника, захвачено объектов: ${reward.capturedObjectives} (+${reward.destructionCredits + reward.captureCredits} Credits, +${reward.destructionXp + reward.captureXp} XP)",
                    ),
                )
            }
            append(campaignText(language, "Equipment returned/lost: ${reward.survived}/${reward.lost}", "Техника вернулась/потеряна: ${reward.survived}/${reward.lost}"))
            if (won) {
                appendLine()
                append(campaignText(language, "Victory bonus active for 7 days: ×1.2 Credits and XP.", "Бонус победителя на 7 дней: ×1,2 Credits и XP."))
            }
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
            WeeklyEventType.FORMATION_MOVED -> campaignText(language, "turn ${event.tick}: $name moved ${event.formationType}", "ход ${event.tick}: $name перемещает соединение ${event.formationType}")
            WeeklyEventType.FORMATION_HIT -> campaignText(language, "turn ${event.tick}: $name hit ${event.targetUnitCode}", "ход ${event.tick}: $name поражает ${event.targetUnitCode}")
            WeeklyEventType.OBJECTIVE_PROGRESS -> campaignText(language, "turn ${event.tick}: $name is securing ${event.objectiveId}", "ход ${event.tick}: $name закрепляется на ${event.objectiveId}")
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

private data class CampaignParticipant(
    val playerId: Long,
    val allianceCode: String,
    val power: Long,
    val survived: Int,
    val lost: Int,
)

private data class ContributionSnapshotRow(
    val id: Long,
    val playerId: Long,
    val power: Long,
    val snapshotJson: String,
    val entryId: String?,
    val tactic: Tactic,
)

private data class DeploymentRow(val mapId: String, val allianceA: String, val allianceB: String)

private data class CampaignReward(
    val id: UUID,
    val xp: Int,
    val credits: Int,
    val materials: Int,
    val survived: Int,
    val lost: Int,
    val destroyedPower: Long,
    val capturedObjectives: Int,
    val destructionCredits: Int,
    val captureCredits: Int,
    val destructionXp: Int,
    val captureXp: Int,
)
