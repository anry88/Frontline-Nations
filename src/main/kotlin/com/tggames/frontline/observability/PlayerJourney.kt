package com.tggames.frontline.observability

import com.tggames.frontline.config.FrontlineProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class JourneyEventType(val value: String) {
    SESSION_STARTED("session_started"),
    USER_ACTION("user_action"),
    NAVIGATION_ERROR("navigation_error"),
    REGISTRATION_COMPLETED("registration_completed"),
    ONBOARDING_COUNTRY_VIEWED("onboarding_country_viewed"),
    ONBOARDING_COUNTRY_SELECTED("onboarding_country_selected"),
    ONBOARDING_CTA_VIEWED("onboarding_cta_viewed"),
    ONBOARDING_CTA_CLICKED("onboarding_cta_clicked"),
    ARMY_VIEWED("army_viewed"),
    ARMY_CHANGED("army_changed"),
    ARMY_RECOVERY_BLOCKED("army_recovery_blocked"),
    ARMY_RECOVERY_STARTED("army_recovery_started"),
    ARMY_RECOVERY_COMPLETED("army_recovery_completed"),
    BATTLE_OFFER_VIEWED("battle_offer_viewed"),
    BATTLE_OFFER_SELECTED("battle_offer_selected"),
    BATTLE_DEPLOYMENT_SELECTED("battle_deployment_selected"),
    BATTLE_STARTED("battle_started"),
    BATTLE_FINISHED("battle_finished"),
    BATTLE_RESULT_DETAILS_OPENED("battle_result_details_opened"),
    POST_BATTLE_ACTION_SELECTED("post_battle_action_selected"),
    POST_BATTLE_ACTION_CLICKED("post_battle_action_clicked"),
    POST_BATTLE_ACTION_COMPLETED("post_battle_action_completed"),
    DAILY_CLAIMED("daily_claimed"),
    SHOP_OPENED("shop_opened"),
    SHOP_PURCHASED("shop_purchased"),
    UPGRADE_COMPLETED("upgrade_completed"),
    FRONT_VIEWED("front_viewed"),
    FRONT_BRIDGE_SHOWN("front_bridge_shown"),
    FRONT_BRIDGE_CLICKED("front_bridge_clicked"),
    CONTRIBUTION_COMMITTED("contribution_committed"),
    CONTRIBUTION_WITHDRAWN("contribution_withdrawn"),
    PERSONAL_BATTLE_BLOCKED_BY_RESERVATION("personal_battle_blocked_by_reservation"),
}

data class JourneyEventDetails(
    val surface: String? = null,
    val result: String? = null,
    val reason: String? = null,
    val variant: String? = null,
    val referenceId: String? = null,
    val battleId: UUID? = null,
    val offerVersion: Long? = null,
    val offerSlot: Int? = null,
    val presetNo: Int? = null,
    val usedCp: Int? = null,
    val entryId: String? = null,
    val objectiveId: String? = null,
    val tactic: String? = null,
    val unitCode: String? = null,
    val quantity: Int? = null,
)

data class JourneySession(val id: UUID, val internal: Boolean)

@Component
class PlayerJourney(
    private val jdbc: JdbcClient,
    private val properties: FrontlineProperties,
    private val clock: Clock,
) {
    private val updateContext = ThreadLocal<Context?>()

    fun <T> withinTelegramUpdate(updateId: Long, action: () -> T): T {
        val previous = updateContext.get()
        updateContext.set(Context(updateId))
        return try {
            action()
        } finally {
            if (previous == null) updateContext.remove() else updateContext.set(previous)
        }
    }

    fun record(telegramId: Long, type: JourneyEventType, details: JourneyEventDetails = JourneyEventDetails()) {
        val context = requireNotNull(updateContext.get()) { "Journey events require a Telegram update context" }
        val now = clock.instant()
        val session = context.sessions.getOrPut(telegramId) { openSession(telegramId, context, now) }
        insertEvent(analyticsProfile(telegramId, lock = false), session.id, context, type, now, details)
    }

    /**
     * Reserves the event sequence while the Telegram update transaction is active, but records the event only
     * after an external delivery succeeds. The returned action is safe to invoke from an after-commit callback.
     */
    fun deferred(telegramId: Long, type: JourneyEventType, details: JourneyEventDetails = JourneyEventDetails()): () -> Unit {
        val context = requireNotNull(updateContext.get()) { "Journey events require a Telegram update context" }
        val now = clock.instant()
        val session = context.sessions.getOrPut(telegramId) { openSession(telegramId, context, now) }
        val sequence = context.nextSequence++
        return {
            insertEvent(
                analyticsProfile(telegramId, lock = false),
                session.id,
                context.updateId,
                sequence,
                type,
                clock.instant(),
                details,
            )
        }
    }

    fun currentSession(telegramId: Long): JourneySession? = updateContext.get()?.sessions?.get(telegramId)?.let {
        JourneySession(it.id, it.internal)
    }

    fun registerPlayer(telegramId: Long) {
        jdbc.sql(
            """
            INSERT INTO analytics_players(player_telegram_id, is_internal)
            SELECT telegram_id, :internal
              FROM players
             WHERE telegram_id = :telegramId
            """.trimIndent(),
        ).param("telegramId", telegramId)
            .param("internal", telegramId in properties.analytics.internalTelegramIds)
            .update()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun markConfiguredInternalAccounts() {
        properties.analytics.internalTelegramIds.forEach { telegramId ->
            jdbc.sql(
                "UPDATE analytics_players SET is_internal = TRUE, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :telegramId",
            ).param("telegramId", telegramId).update()
        }
    }

    @Scheduled(cron = "\${frontline.analytics.cleanup-cron:0 37 3 * * *}", zone = "UTC")
    fun deleteExpiredEvents() {
        val cutoff = clock.instant().minus(properties.analytics.eventRetentionDays, ChronoUnit.DAYS)
        jdbc.sql("DELETE FROM player_journey_events WHERE occurred_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update()
        jdbc.sql("DELETE FROM technical_operation_attempts WHERE started_at < :cutoff")
            .param("cutoff", Timestamp.from(cutoff))
            .update()
    }

    private fun openSession(telegramId: Long, context: Context, now: Instant): Session {
        val profile = analyticsProfile(telegramId, lock = true)
        val previous = jdbc.sql(
            """
            SELECT session_id, occurred_at
              FROM player_journey_events
             WHERE analytics_player_id = :playerId
             ORDER BY occurred_at DESC, id DESC
             LIMIT 1
            """.trimIndent(),
        ).param("playerId", profile.id)
            .query { result, _ -> result.getObject("session_id", UUID::class.java) to result.getTimestamp("occurred_at").toInstant() }
            .optional()
            .orElse(null)
        val timeout = Duration.ofMinutes(properties.analytics.sessionTimeoutMinutes)
        val continues = previous != null && !now.isBefore(previous.second) && Duration.between(previous.second, now) < timeout
        val sessionId = if (continues) previous.first else UUID.randomUUID()
        if (!continues) {
            insertEvent(
                profile,
                sessionId,
                context,
                JourneyEventType.SESSION_STARTED,
                now,
                JourneyEventDetails(surface = "telegram"),
            )
        }
        return Session(sessionId, profile.internal)
    }

    private fun analyticsProfile(telegramId: Long, lock: Boolean): AnalyticsProfile {
        return jdbc.sql(
            """
            SELECT ap.id, ap.is_internal, p.registration_source, p.registration_referral,
                   p.language, p.alliance_code
              FROM analytics_players ap
              JOIN players p ON p.telegram_id = ap.player_telegram_id
             WHERE ap.player_telegram_id = :telegramId
             ${if (lock) "FOR UPDATE" else ""}
            """.trimIndent(),
        ).param("telegramId", telegramId)
            .query { result, _ ->
                AnalyticsProfile(
                    id = result.getLong("id"),
                    internal = result.getBoolean("is_internal"),
                    registrationSource = result.getString("registration_source") ?: "telegram",
                    registrationReferral = result.getString("registration_referral"),
                    locale = result.getString("language") ?: "en",
                    countryCode = result.getString("alliance_code"),
                )
            }.single()
    }

    private fun insertEvent(
        profile: AnalyticsProfile,
        sessionId: UUID,
        context: Context,
        type: JourneyEventType,
        now: Instant,
        details: JourneyEventDetails,
    ) = insertEvent(profile, sessionId, context.updateId, context.nextSequence++, type, now, details)

    private fun insertEvent(
        profile: AnalyticsProfile,
        sessionId: UUID,
        updateId: Long,
        sequence: Int,
        type: JourneyEventType,
        now: Instant,
        details: JourneyEventDetails,
    ) {
        jdbc.sql(
            """
            INSERT INTO player_journey_events(
                analytics_player_id, session_id, telegram_update_id, event_sequence,
                event_name, schema_version, occurred_at, is_internal,
                registration_source, registration_referral, locale, country_code,
                surface, result, reason, variant, reference_id, battle_id,
                offer_version, offer_slot, preset_no, used_cp,
                entry_id, objective_id, tactic, unit_code, quantity
            ) VALUES (
                :playerId, :sessionId, :updateId, :sequence,
                :eventName, :schemaVersion, :occurredAt, :internal,
                :registrationSource, :registrationReferral, :locale, :countryCode,
                :surface, :result, :reason, :variant, :referenceId, :battleId,
                :offerVersion, :offerSlot, :presetNo, :usedCp,
                :entryId, :objectiveId, :tactic, :unitCode, :quantity
            )
            """.trimIndent(),
        ).param("playerId", profile.id)
            .param("sessionId", sessionId)
            .param("updateId", updateId)
            .param("sequence", sequence)
            .param("eventName", type.value)
            .param("schemaVersion", SCHEMA_VERSION)
            .param("occurredAt", Timestamp.from(now))
            .param("internal", profile.internal)
            .param("registrationSource", bounded(profile.registrationSource, 16))
            .param("registrationReferral", bounded(profile.registrationReferral, 64))
            .param("locale", bounded(profile.locale, 8))
            .param("countryCode", bounded(profile.countryCode, 8))
            .param("surface", bounded(details.surface, 32))
            .param("result", bounded(details.result, 32))
            .param("reason", bounded(details.reason, 64))
            .param("variant", bounded(details.variant, 32))
            .param("referenceId", bounded(details.referenceId, 64))
            .param("battleId", details.battleId)
            .param("offerVersion", details.offerVersion)
            .param("offerSlot", details.offerSlot)
            .param("presetNo", details.presetNo)
            .param("usedCp", details.usedCp)
            .param("entryId", bounded(details.entryId, 32))
            .param("objectiveId", bounded(details.objectiveId, 32))
            .param("tactic", bounded(details.tactic, 32))
            .param("unitCode", bounded(details.unitCode, 32))
            .param("quantity", details.quantity)
            .update()
    }

    private fun bounded(value: String?, length: Int): String? = value?.take(length)

    private data class Context(
        val updateId: Long,
        var nextSequence: Int = 0,
        val sessions: MutableMap<Long, Session> = mutableMapOf(),
    )

    private data class Session(val id: UUID, val internal: Boolean)

    private data class AnalyticsProfile(
        val id: Long,
        val internal: Boolean,
        val registrationSource: String,
        val registrationReferral: String?,
        val locale: String,
        val countryCode: String?,
    )

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
