package com.tggames.frontline.feedback

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class FeedbackCommentResult { NONE, STORED, EXPIRED }

@Component
class PlayerFeedbackService(
    private val jdbc: JdbcClient,
    private val telegram: TelegramClient,
    private val properties: FrontlineProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun inlineButton(language: GameLanguage): InlineKeyboardButton =
        InlineKeyboardButton(FeedbackMessages.inlineButton(language), "feedback:open:inline")

    fun markInlineOffer(telegramId: Long, battleId: UUID) {
        if (!properties.feedback.enabled) return
        ensureCampaign()
        jdbc.sql(
            """
            INSERT INTO player_feedback_responses(
                campaign_id, player_telegram_id, is_internal, first_battle_id, inline_offered_at
            ) VALUES (
                :campaign, :player,
                COALESCE((SELECT is_internal FROM analytics_players WHERE player_telegram_id = :player), FALSE),
                :battle, CURRENT_TIMESTAMP
            )
            ON CONFLICT (campaign_id, player_telegram_id) DO UPDATE
                SET first_battle_id = COALESCE(player_feedback_responses.first_battle_id, EXCLUDED.first_battle_id),
                    inline_offered_at = COALESCE(player_feedback_responses.inline_offered_at, EXCLUDED.inline_offered_at),
                    updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("campaign", campaignId()).param("player", telegramId).param("battle", battleId).update()
    }

    fun open(telegramId: Long, chatId: Long, surface: FeedbackSurface): Boolean {
        if (!properties.feedback.enabled) return false
        ensureResponse(telegramId)
        val state = state(telegramId) ?: return false
        val language = language(telegramId)
        if (state.answered || state.skipped) {
            telegram.sendMessage(chatId, if (state.skipped) FeedbackMessages.skipped(language) else FeedbackMessages.thanks(language))
            return false
        }
        jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET opened_at = COALESCE(opened_at, CURRENT_TIMESTAMP),
                   response_surface = COALESCE(response_surface, :surface),
                   updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign AND player_telegram_id = :player
            """.trimIndent(),
        ).param("surface", surface.value).param("campaign", campaignId()).param("player", telegramId).update()
        val question = if (surface == FeedbackSurface.NUDGE && state.trigger != null) {
            FeedbackMessages.nudge(language, state.trigger)
        } else {
            FeedbackMessages.inlineQuestion(language)
        }
        telegram.sendMessage(chatId, question, reasonKeyboard(language))
        return true
    }

    fun answerReason(telegramId: Long, chatId: Long, rawReason: String): Boolean {
        val reason = FeedbackReason.from(rawReason) ?: return false
        if (!properties.feedback.enabled) return false
        ensureResponse(telegramId)
        val updated = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET response_reason = :reason,
                   answered_at = CURRENT_TIMESTAMP,
                   comment_requested_at = CASE WHEN :reason = 'other' THEN CURRENT_TIMESTAMP ELSE comment_requested_at END,
                   updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND answered_at IS NULL
               AND skipped_at IS NULL
            """.trimIndent(),
        ).param("reason", reason.value).param("campaign", campaignId()).param("player", telegramId).update()
        val language = language(telegramId)
        if (updated == 0) {
            telegram.sendMessage(chatId, FeedbackMessages.thanks(language))
            return false
        }
        if (reason == FeedbackReason.OTHER) {
            telegram.sendMessage(chatId, FeedbackMessages.commentPrompt(language))
        } else {
            telegram.sendMessage(
                chatId,
                FeedbackMessages.thanks(language),
                InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(FeedbackMessages.commentButton(language), "feedback:comment")))),
            )
        }
        return true
    }

    fun requestComment(telegramId: Long, chatId: Long): Boolean {
        if (!properties.feedback.enabled) return false
        val updated = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET comment_requested_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND answered_at IS NOT NULL
               AND skipped_at IS NULL
            """.trimIndent(),
        ).param("campaign", campaignId()).param("player", telegramId).update()
        if (updated > 0) telegram.sendMessage(chatId, FeedbackMessages.commentPrompt(language(telegramId)))
        return updated > 0
    }

    fun skip(telegramId: Long, chatId: Long): Boolean {
        if (!properties.feedback.enabled) return false
        ensureResponse(telegramId)
        val updated = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET skipped_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND answered_at IS NULL
               AND skipped_at IS NULL
            """.trimIndent(),
        ).param("campaign", campaignId()).param("player", telegramId).update()
        telegram.sendMessage(chatId, FeedbackMessages.skipped(language(telegramId)))
        return updated > 0
    }

    fun captureComment(telegramId: Long, chatId: Long, rawText: String): FeedbackCommentResult {
        if (!properties.feedback.enabled || rawText.startsWith('/')) return FeedbackCommentResult.NONE
        val text = sanitizeComment(rawText)
        if (text.isBlank()) return FeedbackCommentResult.NONE
        val cutoff = clock.instant().minus(Duration.ofMinutes(properties.feedback.commentWindowMinutes.coerceAtLeast(1)))
        val updated = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET comment_text = :comment,
                   comment_requested_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND answered_at IS NOT NULL
               AND skipped_at IS NULL
               AND comment_text IS NULL
               AND comment_requested_at >= :cutoff
            """.trimIndent(),
        ).param("comment", text).param("campaign", campaignId()).param("player", telegramId)
            .param("cutoff", Timestamp.from(cutoff)).update()
        if (updated > 0) {
            telegram.sendMessage(chatId, FeedbackMessages.thanks(language(telegramId)))
            return FeedbackCommentResult.STORED
        }
        val expired = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET comment_requested_at = NULL, updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND comment_requested_at IS NOT NULL
               AND comment_requested_at < :cutoff
            """.trimIndent(),
        ).param("campaign", campaignId()).param("player", telegramId).param("cutoff", Timestamp.from(cutoff)).update()
        if (expired > 0) {
            telegram.sendMessage(chatId, FeedbackMessages.expired(language(telegramId)))
            return FeedbackCommentResult.EXPIRED
        }
        return FeedbackCommentResult.NONE
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    fun deliverInactivePrompts() {
        if (!properties.feedback.enabled) return
        runCatching {
            ensureCampaign()
            candidates().forEach(::deliverPrompt)
        }.onFailure { logger.warn("Could not process the feedback campaign", it) }
    }

    @Scheduled(cron = "0 52 3 * * *", zone = "UTC")
    fun deleteExpiredComments() {
        if (properties.feedback.retentionDays <= 0) return
        val cutoff = clock.instant().minus(Duration.ofDays(properties.feedback.retentionDays))
        jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET comment_text = NULL, updated_at = CURRENT_TIMESTAMP
             WHERE comment_text IS NOT NULL AND updated_at < :cutoff
            """.trimIndent(),
        ).param("cutoff", Timestamp.from(cutoff)).update()
    }

    private fun candidates(): List<Candidate> {
        val cutoff = clock.instant().minus(Duration.ofHours(properties.feedback.inactivityHours.coerceAtLeast(1)))
        return jdbc.sql(
            """
            SELECT player.telegram_id, player.language, activation.first_battle_id,
                   CASE WHEN activation.first_result_sent_at IS NULL
                        THEN 'inactive_before_first' ELSE 'inactive_after_first' END AS trigger
              FROM analytics_player_activation_all activation
              JOIN analytics_players analytics ON analytics.id = activation.analytics_player_id
              JOIN players player ON player.telegram_id = analytics.player_telegram_id
              JOIN analytics_dropoff_report_all dropoff ON dropoff.analytics_player_id = activation.analytics_player_id
              JOIN feedback_campaigns campaign ON campaign.campaign_id = :campaign AND campaign.active
              LEFT JOIN player_feedback_responses response
                ON response.campaign_id = campaign.campaign_id
               AND response.player_telegram_id = player.telegram_id
             WHERE NOT analytics.is_internal
               AND player.telegram_unavailable_at IS NULL
               AND activation.second_battle_started_at IS NULL
               AND COALESCE(dropoff.last_user_action_at, activation.registered_at) <= :cutoff
               AND (
                    (activation.first_result_sent_at IS NULL AND activation.registered_at >= campaign.started_at)
                    OR activation.first_result_sent_at >= campaign.started_at
               )
               AND response.nudge_status IS NULL
               AND response.answered_at IS NULL
               AND response.skipped_at IS NULL
             ORDER BY COALESCE(dropoff.last_user_action_at, activation.registered_at), player.telegram_id
             LIMIT :limit
            """.trimIndent(),
        ).param("campaign", campaignId()).param("cutoff", Timestamp.from(cutoff))
            .param("limit", properties.feedback.batchSize.coerceIn(1, 100))
            .query { rs, _ ->
                Candidate(
                    telegramId = rs.getLong("telegram_id"),
                    language = GameLanguage.fromStored(rs.getString("language")),
                    battleId = rs.getObject("first_battle_id", UUID::class.java),
                    trigger = FeedbackNudgeTrigger.entries.first { it.value == rs.getString("trigger") },
                )
            }.list()
    }

    private fun deliverPrompt(candidate: Candidate) {
        ensureResponse(candidate.telegramId, candidate.battleId)
        val claimed = jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET nudge_trigger = :trigger, nudge_status = 'sending', updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign
               AND player_telegram_id = :player
               AND nudge_status IS NULL
               AND answered_at IS NULL
               AND skipped_at IS NULL
            """.trimIndent(),
        ).param("trigger", candidate.trigger.value).param("campaign", campaignId())
            .param("player", candidate.telegramId).update()
        if (claimed == 0) return
        try {
            telegram.sendMessage(
                candidate.telegramId,
                FeedbackMessages.nudge(candidate.language, candidate.trigger),
                InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(FeedbackMessages.openButton(candidate.language), "feedback:open:nudge")))),
            )
            markNudge(candidate.telegramId, "sent", true)
        } catch (error: TelegramDeliveryException) {
            markNudge(candidate.telegramId, if (error.playerUnavailable) "unavailable" else "failed", false)
            if (error.playerUnavailable) markPlayerUnavailable(candidate.telegramId)
            logger.warn("A feedback prompt could not be delivered: {}", if (error.playerUnavailable) "player unavailable" else "Telegram delivery failed")
        } catch (error: Exception) {
            markNudge(candidate.telegramId, "failed", false)
            logger.warn("A feedback prompt could not be delivered", error)
        }
    }

    private fun markNudge(telegramId: Long, status: String, sent: Boolean) {
        jdbc.sql(
            """
            UPDATE player_feedback_responses
               SET nudge_status = :status,
                   nudge_sent_at = CASE WHEN :sent THEN CURRENT_TIMESTAMP ELSE nudge_sent_at END,
                   updated_at = CURRENT_TIMESTAMP
             WHERE campaign_id = :campaign AND player_telegram_id = :player
            """.trimIndent(),
        ).param("status", status).param("sent", sent).param("campaign", campaignId()).param("player", telegramId).update()
    }

    private fun markPlayerUnavailable(telegramId: Long) {
        jdbc.sql(
            """
            UPDATE players
               SET telegram_unavailable_at = CURRENT_TIMESTAMP,
                   telegram_unavailable_reason = 'feedback_prompt_rejected',
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :player
            """.trimIndent(),
        ).param("player", telegramId).update()
    }

    private fun ensureCampaign() {
        jdbc.sql(
            """
            INSERT INTO feedback_campaigns(campaign_id, version, active)
            VALUES (:campaign, 1, TRUE)
            ON CONFLICT (campaign_id) DO NOTHING
            """.trimIndent(),
        ).param("campaign", campaignId()).update()
    }

    private fun ensureResponse(telegramId: Long, battleId: UUID? = null) {
        ensureCampaign()
        jdbc.sql(
            """
            INSERT INTO player_feedback_responses(campaign_id, player_telegram_id, is_internal, first_battle_id)
            VALUES (
                :campaign, :player,
                COALESCE((SELECT is_internal FROM analytics_players WHERE player_telegram_id = :player), FALSE),
                :battle
            )
            ON CONFLICT (campaign_id, player_telegram_id) DO UPDATE
                SET first_battle_id = COALESCE(player_feedback_responses.first_battle_id, EXCLUDED.first_battle_id),
                    updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("campaign", campaignId()).param("player", telegramId).param("battle", battleId).update()
    }

    private fun state(telegramId: Long): ResponseState? = jdbc.sql(
        """
        SELECT answered_at IS NOT NULL AS answered, skipped_at IS NOT NULL AS skipped, nudge_trigger
          FROM player_feedback_responses
         WHERE campaign_id = :campaign AND player_telegram_id = :player
        """.trimIndent(),
    ).param("campaign", campaignId()).param("player", telegramId)
        .query { rs, _ ->
            ResponseState(
                rs.getBoolean("answered"),
                rs.getBoolean("skipped"),
                rs.getString("nudge_trigger")?.let { value -> FeedbackNudgeTrigger.entries.firstOrNull { it.value == value } },
            )
        }
        .optional().orElse(null)

    private fun language(telegramId: Long): GameLanguage = jdbc.sql("SELECT language FROM players WHERE telegram_id = :player")
        .param("player", telegramId).query(String::class.java).optional()
        .map(GameLanguage::fromStored).orElse(GameLanguage.EN)

    private fun reasonKeyboard(language: GameLanguage): InlineKeyboardMarkup = InlineKeyboardMarkup(
        FeedbackReason.entries.map { reason ->
            listOf(InlineKeyboardButton(FeedbackMessages.reason(language, reason), "feedback:reason:${reason.value}"))
        } + listOf(listOf(InlineKeyboardButton(FeedbackMessages.skip(language), "feedback:skip"))),
    )

    private fun sanitizeComment(raw: String): String {
        val clean = raw.filter { it == '\n' || !it.isISOControl() }.trim()
        val limit = properties.feedback.commentMaxLength.coerceIn(1, 500)
        val points = clean.codePoints().limit(limit.toLong()).toArray()
        return String(points, 0, points.size)
    }

    private fun campaignId(): String = properties.feedback.campaignId.take(32)

    private data class Candidate(
        val telegramId: Long,
        val language: GameLanguage,
        val battleId: UUID?,
        val trigger: FeedbackNudgeTrigger,
    )

    private data class ResponseState(
        val answered: Boolean,
        val skipped: Boolean,
        val trigger: FeedbackNudgeTrigger?,
    )
}
