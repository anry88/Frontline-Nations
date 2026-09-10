package com.tggames.frontline.campaign

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.game.AllianceCatalog
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
) {
    private val calendar = CampaignCalendar(ZoneId.of(properties.gameTimezone))
    private val battlefields = listOf(
        "Карпатский перевал",
        "Дунайская долина",
        "Побережье Адриатики",
        "Патагонийское плато",
        "Сахарский коридор",
        "Алтайский рубеж",
        "Полесский рубеж",
        "Северная тундра",
    )

    @Transactional
    fun ensureCurrentWeek(): CampaignPeriod {
        val period = calendar.periodAt(clock.instant())
        jdbc.sql(
            """
            INSERT INTO campaign_weeks(week_key, scheduled_at)
            VALUES (:week, :scheduledAt)
            ON CONFLICT (week_key) DO NOTHING
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("scheduledAt", Timestamp.from(period.resolvesAt.toInstant()))
            .update()
        ensureMatchups(period)
        return period
    }

    @Transactional
    fun contribute(playerId: Long, allianceCode: String, amount: Int): ContributionOutcome {
        val period = ensureCurrentWeek()
        val week = jdbc.sql(
            "SELECT status, scheduled_at FROM campaign_weeks WHERE week_key = :week FOR UPDATE",
        ).param("week", period.weekKey)
            .query { rs, _ -> rs.getString("status") to rs.getTimestamp("scheduled_at").toInstant() }
            .single()
        if (week.first != "OPEN" || !clock.instant().isBefore(week.second)) {
            return ContributionOutcome(false, "Вклады закрыты: недельное сражение уже началось. Итог появится в /front.")
        }

        val debited = jdbc.sql(
            "UPDATE players SET credits = credits - :amount, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND credits >= :amount",
        ).param("amount", amount)
            .param("id", playerId)
            .update()
        if (debited == 0) {
            val balance = jdbc.sql("SELECT credits FROM players WHERE telegram_id = :id")
                .param("id", playerId)
                .query(Long::class.java)
                .single()
            return ContributionOutcome(false, "Недостаточно Credits. Текущий баланс: $balance.")
        }

        jdbc.sql(
            """
            INSERT INTO campaign_contributions(week_key, player_telegram_id, alliance_code, credits, power)
            VALUES (:week, :playerId, :alliance, :amount, :power)
            """.trimIndent(),
        ).param("week", period.weekKey)
            .param("playerId", playerId)
            .param("alliance", allianceCode)
            .param("amount", amount)
            .param("power", amount)
            .update()
        jdbc.sql(
            """
            INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason)
            VALUES (:playerId, 'CREDITS', :delta, 'CAMPAIGN_CONTRIBUTION')
            """.trimIndent(),
        ).param("playerId", playerId)
            .param("delta", -amount.toLong())
            .update()
        return ContributionOutcome(true, "Вклад принят: $amount Credits → $amount силы фронта.")
    }

    @Transactional
    fun frontText(allianceCode: String?): String {
        val period = ensureCurrentWeek()
        val matchups = matchupRows(period.weekKey)
        val ownMatchup = allianceCode?.let { code ->
            matchups.firstOrNull { it.allianceA == code || it.allianceB == code }
        }
        val header = buildString {
            appendLine("🌍 НЕДЕЛЬНЫЙ ФРОНТ ${period.weekKey}")
            appendLine("⚔️ Сражение: воскресенье, ${period.resolvesAt.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))} · Belgrade")
            append(scheduleStatus(period, matchups.firstOrNull()?.status))
        }
        if (ownMatchup == null) {
            val pairs = matchups.joinToString("\n") {
                "${AllianceCatalog.name(it.allianceA)} — ${AllianceCatalog.name(it.allianceB)}"
            }
            return "$header\n\nПары недели:\n$pairs\n\nВыберите альянс через /start, чтобы участвовать."
        }
        return if (ownMatchup.status == "RESOLVED") {
            "$header\n\n${resolvedMatchupText(ownMatchup, allianceCode)}"
        } else {
            "$header\n\n${openMatchupText(ownMatchup, allianceCode)}"
        }
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
        val existing = jdbc.sql("SELECT COUNT(*) FROM campaign_matchups WHERE week_key = :week")
            .param("week", period.weekKey)
            .query(Int::class.java)
            .single()
        if (existing > 0) return

        val previousWeek = calendar.periodAt(period.opensAt.toInstant().minus(Duration.ofDays(1))).weekKey
        val previousPower = jdbc.sql(
            """
            SELECT alliance_code, COALESCE(SUM(power), 0) AS total
              FROM campaign_contributions
             WHERE week_key = :week
             GROUP BY alliance_code
            """.trimIndent(),
        ).param("week", previousWeek)
            .query { rs, _ -> rs.getString("alliance_code") to rs.getLong("total") }
            .list()
            .toMap()
        val ranked = AllianceCatalog.all.keys.sortedWith(
            compareByDescending<String> { previousPower[it] ?: 0L }
                .thenBy { UUID.nameUUIDFromBytes("${period.weekKey}:$it".toByteArray(StandardCharsets.UTF_8)).toString() },
        )
        ranked.chunked(2).forEachIndexed { pairIndex, pair ->
            if (pair.size < 2) return@forEachIndexed
            val matchupId = UUID.nameUUIDFromBytes(
                "${period.weekKey}:$pairIndex:${pair[0]}:${pair[1]}".toByteArray(StandardCharsets.UTF_8),
            )
            val battlefield = battlefields[Math.floorMod(period.weekKey.hashCode() + pairIndex, battlefields.size)]
            jdbc.sql(
                """
                INSERT INTO campaign_matchups(id, week_key, pair_index, battlefield, alliance_a, alliance_b)
                VALUES (:id, :week, :pairIndex, :battlefield, :allianceA, :allianceB)
                ON CONFLICT (week_key, pair_index) DO NOTHING
                """.trimIndent(),
            ).param("id", matchupId)
                .param("week", period.weekKey)
                .param("pairIndex", pairIndex)
                .param("battlefield", battlefield)
                .param("allianceA", pair[0])
                .param("allianceB", pair[1])
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
            val result = battleEngine.resolve(
                properties.battleServerSalt,
                weekKey,
                matchup.pairIndex,
                matchup.battlefield,
                forceA,
                forceB,
                campaignBalance(),
            )
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
                       winner_code = :winner,
                       battle_seed = :seed,
                       seed_hash = :seedHash,
                       engine_version = 1,
                       events_json = CAST(:events AS jsonb),
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
                .param("winner", result.winnerCode)
                .param("seed", result.seed)
                .param("seedHash", result.seedHash)
                .param("events", objectMapper.writeValueAsString(result.events))
                .param("id", matchup.id)
                .update()
            issueRewardsAndNotifications(weekKey, matchup, result)
        }
        jdbc.sql(
            "UPDATE campaign_weeks SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE week_key = :week AND status = 'OPEN'",
        ).param("week", weekKey).update()
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
            "SELECT telegram_id, alliance_code FROM players WHERE alliance_code IN (:allianceA, :allianceB)",
        ).param("allianceA", matchup.allianceA)
            .param("allianceB", matchup.allianceB)
            .query { rs, _ -> rs.getLong("telegram_id") to rs.getString("alliance_code") }
            .list()
        players.forEach { (playerId, allianceCode) ->
            val message = notificationText(matchup, result, allianceCode, rewardsByPlayer[playerId])
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
        SELECT COALESCE(SUM(power), 0) AS power, COUNT(DISTINCT player_telegram_id) AS contributors
          FROM campaign_contributions
         WHERE week_key = :week AND alliance_code = :alliance
        """.trimIndent(),
    ).param("week", weekKey)
        .param("alliance", allianceCode)
        .query { rs, _ -> AllianceForce(allianceCode, rs.getLong("power"), rs.getInt("contributors")) }
        .single()

    private fun matchupRows(weekKey: String): List<MatchupRow> = jdbc.sql(
        """
        SELECT m.id, m.pair_index, m.battlefield, m.alliance_a, m.alliance_b,
               m.contribution_a, m.contribution_b, m.npc_bonus_a, m.npc_bonus_b,
               m.score_a, m.score_b, m.winner_code, m.events_json, w.status
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
                allianceA = rs.getString("alliance_a"),
                allianceB = rs.getString("alliance_b"),
                contributionA = rs.getLong("contribution_a"),
                contributionB = rs.getLong("contribution_b"),
                npcBonusA = rs.getInt("npc_bonus_a"),
                npcBonusB = rs.getInt("npc_bonus_b"),
                scoreA = rs.getLong("score_a").takeUnless { rs.wasNull() },
                scoreB = rs.getLong("score_b").takeUnless { rs.wasNull() },
                winnerCode = rs.getString("winner_code"),
                eventsJson = rs.getString("events_json"),
                status = rs.getString("status"),
            )
        }.list()

    private fun openMatchupText(matchup: MatchupRow, allianceCode: String): String {
        val ownIsA = matchup.allianceA == allianceCode
        val ownCode = if (ownIsA) matchup.allianceA else matchup.allianceB
        val enemyCode = if (ownIsA) matchup.allianceB else matchup.allianceA
        val ownPower = liveContribution(matchup, ownCode)
        val enemyPower = liveContribution(matchup, enemyCode)
        val signal = when {
            ownPower == 0L && enemyPower == 0L -> "разведка пока не фиксирует перевеса"
            ownPower * 10 < enemyPower * 8 -> "противник наращивает преимущество"
            ownPower * 10 > enemyPower * 12 -> "ваш альянс удерживает инициативу"
            else -> "силы сторон близки"
        }
        return buildString {
            appendLine("🗺 ${matchup.battlefield}")
            appendLine("${AllianceCatalog.name(ownCode)} против ${AllianceCatalog.name(enemyCode)}")
            appendLine()
            appendLine("Ваш подтверждённый вклад: $ownPower")
            appendLine("Разведсводка: $signal.")
            appendLine("Малой стороне будет добавлена ограниченная NPC-компенсация.")
            appendLine()
            append("Усилить сторону: /contribute 100")
        }
    }

    private fun resolvedMatchupText(matchup: MatchupRow, allianceCode: String): String {
        val winner = matchup.winnerCode ?: return "Результат ещё рассчитывается."
        val ownWon = allianceCode == winner
        val events: List<WeeklyBattleEvent> = objectMapper.readValue(
            matchup.eventsJson,
            object : TypeReference<List<WeeklyBattleEvent>>() {},
        )
        val highlights = events.joinToString("\n") { "• ${it.phase}: ${replaceCodes(it.text, matchup)}" }
        return buildString {
            appendLine("🗺 ${matchup.battlefield}")
            appendLine(if (ownWon) "🏆 ВАШ АЛЬЯНС ПОБЕДИЛ" else "🎖 НЕДЕЛЬНОЕ СРАЖЕНИЕ ЗАВЕРШЕНО")
            appendLine("${AllianceCatalog.name(matchup.allianceA)} ${matchup.scoreA} : ${matchup.scoreB} ${AllianceCatalog.name(matchup.allianceB)}")
            appendLine("NPC-компенсация: +${matchup.npcBonusA} / +${matchup.npcBonusB}")
            appendLine()
            append(highlights)
        }
    }

    private fun notificationText(
        matchup: MatchupRow,
        result: WeeklyBattleResult,
        allianceCode: String,
        reward: CampaignReward?,
    ): String = buildString {
        val won = allianceCode == result.winnerCode
        appendLine("🌍 НЕДЕЛЬНОЕ СРАЖЕНИЕ ЗАВЕРШЕНО")
        appendLine("${matchup.battlefield}")
        appendLine()
        appendLine(if (won) "🏆 Ваш альянс победил!" else "🎖 Ваш альянс завершил кампанию.")
        appendLine("${AllianceCatalog.name(matchup.allianceA)} ${result.scoreA} : ${result.scoreB} ${AllianceCatalog.name(matchup.allianceB)}")
        if (reward != null) {
            appendLine()
            appendLine("Награда за вклад:")
            appendLine("+${reward.xp} XP · +${reward.credits} Credits")
            append("+${reward.research} Research Points · +${reward.materials} Materials")
        } else {
            appendLine()
            append("Награда выдаётся командирам, внесшим вклад до блокировки.")
        }
    }

    private fun liveContribution(matchup: MatchupRow, allianceCode: String): Long = jdbc.sql(
        "SELECT COALESCE(SUM(power), 0) FROM campaign_contributions WHERE week_key = (SELECT week_key FROM campaign_matchups WHERE id = :id) AND alliance_code = :alliance",
    ).param("id", matchup.id)
        .param("alliance", allianceCode)
        .query(Long::class.java)
        .single()

    private fun scheduleStatus(period: CampaignPeriod, status: String?): String {
        if (status == "RESOLVED") return "Статус: результаты опубликованы"
        val remaining = Duration.between(clock.instant(), period.resolvesAt.toInstant())
        if (remaining.isNegative || remaining.isZero) return "Статус: идёт расчёт результатов"
        val days = remaining.toDays()
        val hours = remaining.minusDays(days).toHours()
        return "До блокировки вкладов: ${days}д ${hours}ч"
    }

    private fun replaceCodes(text: String, matchup: MatchupRow): String = text
        .replace(matchup.allianceA, AllianceCatalog.name(matchup.allianceA))
        .replace(matchup.allianceB, AllianceCatalog.name(matchup.allianceB))

    private fun campaignBalance(): WeeklyBalance {
        val c = properties.campaign
        return WeeklyBalance(
            c.npcBasePower,
            c.npcPerMissingContributor,
            c.maxNpcCompensation,
            c.contributionSoftCap,
            c.overflowDivisor,
        )
    }
}

private data class MatchupRow(
    val id: UUID,
    val pairIndex: Int,
    val battlefield: String,
    val allianceA: String,
    val allianceB: String,
    val contributionA: Long,
    val contributionB: Long,
    val npcBonusA: Int,
    val npcBonusB: Int,
    val scoreA: Long?,
    val scoreB: Long?,
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
