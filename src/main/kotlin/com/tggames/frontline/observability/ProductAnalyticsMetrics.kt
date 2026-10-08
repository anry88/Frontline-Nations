package com.tggames.frontline.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class ProductAnalyticsMetrics(
    private val registry: MeterRegistry,
    private val jdbc: JdbcClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val activation = ACTIVATION_STEPS.associateWith { gaugeLong("frontline.product.activation.players", "step", it) }
    private val ttfb = listOf("p50", "p90").associateWith { gaugeDouble("frontline.product.activation.ttfb", "quantile", it, "unit", "seconds") }
    private val secondBattle = SECOND_BATTLE_WINDOWS.associateWith { gaugeLong("frontline.product.activation.second.battle", "window", it) }
    private val retention = buildMap {
        RETENTION_DAYS.forEach { day ->
            RETENTION_STATUSES.forEach { status ->
                put(RetentionKey(day, status), gaugeLong("frontline.product.retention.players", "day", day, "status", status))
            }
        }
    }
    private val dropoff = ConcurrentHashMap<DropoffKey, AtomicLong>()
    private val coverage = listOf("start", "end").associateWith { gaugeLong("frontline.product.coverage", "boundary", it, "unit", "epoch_seconds") }
    private val postBattleActions = buildMap {
        POST_BATTLE_ACTIONS.forEach { action ->
            POST_BATTLE_STATUSES.forEach { status ->
                put(PostBattleKey(action, status), gaugeLong("frontline.product.post.battle.action", "action", action, "status", status))
            }
        }
    }
    private val battleResultEngagement = POST_BATTLE_RESULT_STATUSES.associateWith {
        gaugeLong("frontline.product.battle.result", "status", it)
    }
    private val armyRecovery = ARMY_RECOVERY_STATUSES.associateWith { gaugeLong("frontline.product.army.recovery.players", "status", it) }
    private val armyRecoveryTime = listOf("p50", "p90").associateWith {
        gaugeDouble("frontline.product.army.recovery.time", "quantile", it, "unit", "seconds")
    }
    private val navigationErrors = buildMap {
        NAVIGATION_ERROR_CATEGORIES.forEach { category ->
            NAVIGATION_STAGES.forEach { stage ->
                put(NavigationKey(category, stage), gaugeLong("frontline.product.navigation.errors", "category", category, "stage", stage))
            }
        }
    }
    private val navigationRecovery = NAVIGATION_RECOVERY_STATUSES.associateWith {
        gaugeLong("frontline.product.navigation.recovery.players", "status", it)
    }
    private val frontBridge = FRONT_BRIDGE_STATUSES.associateWith {
        gaugeLong("frontline.product.front.bridge.players", "status", it)
    }
    private val feedback = FEEDBACK_STATUSES.associateWith {
        gaugeLong("frontline.product.feedback.players", "status", it)
    }
    private val feedbackReasons = FEEDBACK_REASONS.associateWith {
        gaugeLong("frontline.product.feedback.reason", "reason", it)
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    fun refresh() {
        runCatching {
            refreshActivation()
            refreshRetention()
            refreshDropoff()
            refreshCoverage()
            refreshPostBattleActions()
            refreshArmyRecovery()
            refreshNavigationRecovery()
            refreshFrontBridge()
            refreshFeedback()
        }.onFailure { logger.warn("Could not refresh product analytics metrics", it) }
    }

    private fun refreshActivation() {
        val row = jdbc.sql(
            """
            SELECT COUNT(*) AS registration,
                   SUM(CASE WHEN country_selected_at IS NOT NULL THEN 1 ELSE 0 END) AS country_selected,
                   SUM(CASE WHEN offer_viewed_at IS NOT NULL THEN 1 ELSE 0 END) AS offer_viewed,
                   SUM(CASE WHEN deployment_completed_at IS NOT NULL THEN 1 ELSE 0 END) AS deployment_completed,
                   SUM(CASE WHEN first_battle_started_at IS NOT NULL THEN 1 ELSE 0 END) AS first_battle_started,
                   SUM(CASE WHEN first_battle_finished_at IS NOT NULL THEN 1 ELSE 0 END) AS first_battle_finished,
                   SUM(CASE WHEN first_result_sent_at IS NOT NULL THEN 1 ELSE 0 END) AS result_sent,
                   SUM(CASE WHEN second_battle_started_at IS NOT NULL THEN 1 ELSE 0 END) AS second_battle_started,
                   SUM(CASE WHEN first_battle_started_at IS NULL THEN 1 ELSE 0 END) AS never_started,
                   SUM(CASE WHEN second_battle_within_24h_after_finish THEN 1 ELSE 0 END) AS second_after_finish,
                   SUM(CASE WHEN second_battle_within_24h_after_registration THEN 1 ELSE 0 END) AS second_after_registration
              FROM analytics_player_activation_all
             WHERE NOT is_internal
            """.trimIndent(),
        ).query { rs, _ ->
            ActivationCounts(
                steps = ACTIVATION_STEPS.associateWith { rs.getLong(it) },
                neverStarted = rs.getLong("never_started"),
                secondAfterFinish = rs.getLong("second_after_finish"),
                secondAfterRegistration = rs.getLong("second_after_registration"),
            )
        }.single()
        row.steps.forEach { (step, count) -> activation.getValue(step).set(count) }
        secondBattle.getValue("never_started").set(row.neverStarted)
        secondBattle.getValue("within_24h_after_first_finish").set(row.secondAfterFinish)
        secondBattle.getValue("within_24h_after_registration").set(row.secondAfterRegistration)

        val values = jdbc.sql(
            "SELECT ttfb_ms FROM analytics_player_activation_all WHERE NOT is_internal AND ttfb_ms IS NOT NULL",
        ).query(Long::class.java).list().map { it / 1000.0 }
        ttfb.getValue("p50").set(quantile(values, 0.50))
        ttfb.getValue("p90").set(quantile(values, 0.90))
    }

    private fun refreshRetention() {
        val row = jdbc.sql(
            """
            SELECT SUM(CASE WHEN d1_matured THEN 1 ELSE 0 END) AS d1_matured,
                   SUM(CASE WHEN d1_matured AND d1_retained THEN 1 ELSE 0 END) AS d1_retained,
                   SUM(CASE WHEN d3_matured THEN 1 ELSE 0 END) AS d3_matured,
                   SUM(CASE WHEN d3_matured AND d3_retained THEN 1 ELSE 0 END) AS d3_retained,
                   SUM(CASE WHEN d7_matured THEN 1 ELSE 0 END) AS d7_matured,
                   SUM(CASE WHEN d7_matured AND d7_retained THEN 1 ELSE 0 END) AS d7_retained
              FROM analytics_retention_cohorts_all
             WHERE NOT is_internal
            """.trimIndent(),
        ).query { rs, _ ->
            buildMap {
                RETENTION_DAYS.forEach { day ->
                    put(RetentionKey(day, "matured"), rs.getLong("${day}_matured"))
                    put(RetentionKey(day, "retained"), rs.getLong("${day}_retained"))
                }
            }
        }.single()
        row.forEach { (key, count) -> retention.getValue(key).set(count) }
    }

    private fun refreshDropoff() {
        val values = jdbc.sql(
            """
            SELECT last_activation_stage, observed_technical_context, COUNT(*) AS players
              FROM analytics_dropoff_report_all
             WHERE NOT is_internal
             GROUP BY last_activation_stage, observed_technical_context
            """.trimIndent(),
        ).query { rs, _ ->
            DropoffKey(rs.getString("last_activation_stage"), normalizeDropoffContext(rs.getString("observed_technical_context"))) to rs.getLong("players")
        }.list().filter { (key, _) -> key.stage in ACTIVATION_STAGES_FOR_DROPOFF }.toMap()

        dropoff.keys.filter { it !in values }.forEach { key -> dropoff.getValue(key).set(0) }
        values.forEach { (key, value) ->
            dropoff.computeIfAbsent(key) {
                gaugeLong("frontline.product.dropoff.players", "stage", key.stage, "context", key.context)
            }.set(value)
        }
    }

    private fun refreshCoverage() {
        val range = jdbc.sql(
            """
            SELECT MIN(occurred_at) AS coverage_start, MAX(occurred_at) AS coverage_end
              FROM player_journey_events
             WHERE NOT is_internal
            """.trimIndent(),
        ).query { rs, _ ->
            (rs.getTimestamp("coverage_start")?.toInstant()?.epochSecond ?: 0L) to
                (rs.getTimestamp("coverage_end")?.toInstant()?.epochSecond ?: 0L)
        }.single()
        coverage.getValue("start").set(range.first)
        coverage.getValue("end").set(range.second)
    }

    private fun refreshPostBattleActions() {
        val values = jdbc.sql(
            """
            SELECT bna.action,
                   SUM(CASE WHEN bna.sent_at IS NOT NULL THEN 1 ELSE 0 END) AS sent,
                   SUM(CASE WHEN bna.clicked_at IS NOT NULL THEN 1 ELSE 0 END) AS clicked,
                   SUM(CASE WHEN bna.completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completed
              FROM battle_next_actions bna
              JOIN analytics_players ap ON ap.player_telegram_id = bna.player_telegram_id
             WHERE NOT ap.is_internal
             GROUP BY bna.action
            """.trimIndent(),
        ).query { rs, _ ->
            val action = rs.getString("action")
            mapOf(
                PostBattleKey(action, "sent") to rs.getLong("sent"),
                PostBattleKey(action, "clicked") to rs.getLong("clicked"),
                PostBattleKey(action, "completed") to rs.getLong("completed"),
            )
        }.list().flatMap { it.entries }.associate { it.toPair() }
        postBattleActions.forEach { (key, gauge) -> gauge.set(values[key] ?: 0L) }

        val engagement = jdbc.sql(
            """
            SELECT SUM(CASE WHEN bna.sent_at IS NOT NULL THEN 1 ELSE 0 END) AS sent,
                   SUM(CASE WHEN bna.details_opened_at IS NOT NULL THEN 1 ELSE 0 END) AS details_opened
              FROM battle_next_actions bna
              JOIN analytics_players ap ON ap.player_telegram_id = bna.player_telegram_id
             WHERE NOT ap.is_internal
            """.trimIndent(),
        ).query { rs, _ ->
            mapOf("sent" to rs.getLong("sent"), "details_opened" to rs.getLong("details_opened"))
        }.single()
        battleResultEngagement.forEach { (status, gauge) -> gauge.set(engagement.getValue(status)) }
    }

    private fun refreshArmyRecovery() {
        val rows = jdbc.sql(
            """
            WITH blocked AS (
                SELECT analytics_player_id, MIN(occurred_at) AS blocked_at
                  FROM player_journey_events
                 WHERE NOT is_internal AND event_name = 'army_recovery_blocked'
                 GROUP BY analytics_player_id
            ), completed AS (
                SELECT blocked.analytics_player_id, blocked.blocked_at,
                       MIN(event.occurred_at) AS completed_at
                  FROM blocked
                  LEFT JOIN player_journey_events event
                    ON event.analytics_player_id = blocked.analytics_player_id
                   AND NOT event.is_internal
                   AND event.event_name = 'army_recovery_completed'
                   AND event.occurred_at >= blocked.blocked_at
                 GROUP BY blocked.analytics_player_id, blocked.blocked_at
            )
            SELECT analytics_player_id, blocked_at, completed_at
              FROM completed
            """.trimIndent(),
        ).query { rs, _ ->
            RecoveryTiming(
                playerId = rs.getLong("analytics_player_id"),
                blockedAt = rs.getTimestamp("blocked_at").toInstant(),
                completedAt = rs.getTimestamp("completed_at")?.toInstant(),
            )
        }.list()
        armyRecovery.getValue("blocked").set(rows.size.toLong())
        armyRecovery.getValue("completed").set(rows.count { it.completedAt != null }.toLong())
        val durations = rows.mapNotNull { row -> row.completedAt?.let { java.time.Duration.between(row.blockedAt, it).toMillis() / 1000.0 } }
        armyRecoveryTime.getValue("p50").set(quantile(durations, 0.50))
        armyRecoveryTime.getValue("p90").set(quantile(durations, 0.90))

        val starts = jdbc.sql(
            """
            WITH completed AS (
                SELECT analytics_player_id, session_id, MIN(occurred_at) AS completed_at
                  FROM player_journey_events
                 WHERE NOT is_internal AND event_name = 'army_recovery_completed'
                 GROUP BY analytics_player_id, session_id
            )
            SELECT completed.analytics_player_id, completed.session_id, completed.completed_at,
                   battle.session_id AS battle_session_id, battle.occurred_at AS battle_at
              FROM completed
              LEFT JOIN player_journey_events battle
                ON battle.analytics_player_id = completed.analytics_player_id
               AND NOT battle.is_internal
               AND battle.event_name = 'battle_started'
               AND battle.occurred_at > completed.completed_at
            """.trimIndent(),
        ).query { rs, _ ->
            RecoveryBattleStart(
                playerId = rs.getLong("analytics_player_id"),
                completionSessionId = rs.getObject("session_id", java.util.UUID::class.java),
                completedAt = rs.getTimestamp("completed_at").toInstant(),
                battleSessionId = rs.getObject("battle_session_id", java.util.UUID::class.java),
                battleAt = rs.getTimestamp("battle_at")?.toInstant(),
            )
        }.list()
        armyRecovery.getValue("next_battle_same_session").set(
            starts.filter { it.battleAt != null && it.battleSessionId == it.completionSessionId }.map { it.playerId }.distinct().size.toLong(),
        )
        armyRecovery.getValue("next_battle_24h").set(
            starts.filter { it.battleAt != null && it.battleAt.isBefore(it.completedAt.plus(java.time.Duration.ofHours(24))) }
                .map { it.playerId }.distinct().size.toLong(),
        )
    }

    private fun refreshNavigationRecovery() {
        val errors = jdbc.sql(
            """
            SELECT analytics_player_id, session_id, occurred_at, surface, reason
              FROM player_journey_events
             WHERE NOT is_internal AND event_name = 'navigation_error'
            """.trimIndent(),
        ).query { rs, _ ->
            NavigationErrorEvent(
                playerId = rs.getLong("analytics_player_id"),
                sessionId = rs.getObject("session_id", java.util.UUID::class.java),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                key = NavigationKey(rs.getString("surface"), rs.getString("reason")),
            )
        }.list()
        val counts = errors.groupingBy { it.key }.eachCount()
        navigationErrors.forEach { (key, gauge) -> gauge.set((counts[key] ?: 0).toLong()) }
        val laterActions = jdbc.sql(
            """
            SELECT analytics_player_id, session_id, occurred_at
              FROM player_journey_events
             WHERE NOT is_internal
               AND event_name NOT IN ('session_started', 'user_action', 'navigation_error')
            """.trimIndent(),
        ).query { rs, _ ->
            NavigationActionEvent(
                playerId = rs.getLong("analytics_player_id"),
                sessionId = rs.getObject("session_id", java.util.UUID::class.java),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        }.list()
        navigationRecovery.getValue("errored").set(errors.map { it.playerId }.distinct().size.toLong())
        navigationRecovery.getValue("repeat_error").set(
            errors.groupingBy { it.playerId }.eachCount().count { it.value > 1 }.toLong(),
        )
        navigationRecovery.getValue("recovered_same_session").set(
            errors.filter { error -> laterActions.any { it.playerId == error.playerId && it.sessionId == error.sessionId && it.occurredAt > error.occurredAt } }
                .map { it.playerId }.distinct().size.toLong(),
        )
    }

    private fun refreshFrontBridge() {
        val offerCounts = jdbc.sql(
            """
            SELECT SUM(CASE WHEN offer.shown_at IS NOT NULL THEN 1 ELSE 0 END) AS shown,
                   SUM(CASE WHEN offer.clicked_at IS NOT NULL THEN 1 ELSE 0 END) AS clicked
              FROM front_bridge_offers offer
              JOIN analytics_players player ON player.player_telegram_id = offer.player_telegram_id
             WHERE NOT player.is_internal
            """.trimIndent(),
        ).query { rs, _ ->
            mapOf("shown" to rs.getLong("shown"), "clicked" to rs.getLong("clicked"))
        }.single()
        val events = jdbc.sql(
            """
            SELECT analytics_player_id, session_id, event_name, occurred_at
              FROM player_journey_events
             WHERE NOT is_internal
               AND event_name IN (
                   'front_bridge_shown', 'user_action', 'contribution_committed',
                   'contribution_withdrawn', 'personal_battle_blocked_by_reservation'
               )
             ORDER BY occurred_at
            """.trimIndent(),
        ).query { rs, _ ->
            FrontBridgeEvent(
                playerId = rs.getLong("analytics_player_id"),
                sessionId = rs.getObject("session_id", java.util.UUID::class.java),
                name = rs.getString("event_name"),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        }.list()
        val shownByPlayer = events.filter { it.name == "front_bridge_shown" }.groupBy { it.playerId }
            .mapValues { (_, values) -> values.minBy { it.occurredAt } }
        val afterShown = { event: FrontBridgeEvent ->
            shownByPlayer[event.playerId]?.let { !event.occurredAt.isBefore(it.occurredAt) } == true
        }
        frontBridge.getValue("shown").set(offerCounts.getValue("shown"))
        frontBridge.getValue("clicked").set(offerCounts.getValue("clicked"))
        frontBridge.getValue("contributed").set(events.filter { it.name == "contribution_committed" && afterShown(it) }.map { it.playerId }.distinct().size.toLong())
        frontBridge.getValue("withdrawn").set(events.filter { it.name == "contribution_withdrawn" && afterShown(it) }.map { it.playerId }.distinct().size.toLong())
        frontBridge.getValue("returned_session").set(
            events.filter { event ->
                event.name == "user_action" && shownByPlayer[event.playerId]?.let {
                    event.occurredAt.isAfter(it.occurredAt) && event.sessionId != it.sessionId
                } == true
            }.map { it.playerId }.distinct().size.toLong(),
        )
        frontBridge.getValue("reservation_blocked").set(
            events.filter { it.name == "personal_battle_blocked_by_reservation" }.map { it.playerId }.distinct().size.toLong(),
        )
    }

    private fun refreshFeedback() {
        val counts = jdbc.sql(
            """
            SELECT SUM(CASE WHEN inline_offered_at IS NOT NULL THEN 1 ELSE 0 END) AS inline_offered,
                   SUM(CASE WHEN nudge_sent_at IS NOT NULL THEN 1 ELSE 0 END) AS nudge_sent,
                   SUM(CASE WHEN opened_at IS NOT NULL THEN 1 ELSE 0 END) AS opened,
                   SUM(CASE WHEN answered_at IS NOT NULL THEN 1 ELSE 0 END) AS answered,
                   SUM(CASE WHEN skipped_at IS NOT NULL THEN 1 ELSE 0 END) AS skipped,
                   SUM(CASE WHEN commented THEN 1 ELSE 0 END) AS commented
              FROM analytics_feedback_responses
            """.trimIndent(),
        ).query { rs, _ -> FEEDBACK_STATUSES.associateWith(rs::getLong) }.single()
        feedback.forEach { (status, gauge) -> gauge.set(counts.getValue(status)) }

        val reasons = jdbc.sql(
            """
            SELECT response_reason, COUNT(*) AS players
              FROM analytics_feedback_responses
             WHERE response_reason IS NOT NULL
             GROUP BY response_reason
            """.trimIndent(),
        ).query { rs, _ -> rs.getString("response_reason") to rs.getLong("players") }.list().toMap()
        feedbackReasons.forEach { (reason, gauge) -> gauge.set(reasons[reason] ?: 0L) }
    }

    private fun gaugeLong(name: String, vararg tags: String): AtomicLong = AtomicLong().also { value ->
        Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags).register(registry)
    }

    private fun gaugeDouble(name: String, vararg tags: String): AtomicReference<Double> = AtomicReference(0.0).also { value ->
        Gauge.builder(name, value) { it.get() }.tags(*tags).register(registry)
    }

    companion object {
        internal val ACTIVATION_STEPS = listOf(
            "registration", "country_selected", "offer_viewed", "deployment_completed",
            "first_battle_started", "first_battle_finished", "result_sent", "second_battle_started",
        )
        private val ACTIVATION_STAGES_FOR_DROPOFF = setOf(
            "registration", "country_selected", "offer_viewed", "deployment_completed",
            "first_battle_started", "first_battle_finished", "result_sent",
        )
        private val RETENTION_DAYS = listOf("d1", "d3", "d7")
        private val RETENTION_STATUSES = listOf("matured", "retained")
        private val SECOND_BATTLE_WINDOWS = listOf("never_started", "within_24h_after_first_finish", "within_24h_after_registration")
        private val POST_BATTLE_ACTIONS = listOf("next_battle", "restore_group", "choose_group")
        private val POST_BATTLE_STATUSES = listOf("sent", "clicked", "completed")
        private val POST_BATTLE_RESULT_STATUSES = listOf("sent", "details_opened")
        private val ARMY_RECOVERY_STATUSES = listOf("blocked", "completed", "next_battle_same_session", "next_battle_24h")
        private val NAVIGATION_ERROR_CATEGORIES = listOf("unknown_command", "unexpected_text", "stale_callback")
        private val NAVIGATION_STAGES = listOf("country", "nickname_confirmation", "first_operation", "next_battle", "army_recovery")
        private val NAVIGATION_RECOVERY_STATUSES = listOf("errored", "recovered_same_session", "repeat_error")
        private val FRONT_BRIDGE_STATUSES = listOf("shown", "clicked", "contributed", "withdrawn", "returned_session", "reservation_blocked")
        private val FEEDBACK_STATUSES = listOf("inline_offered", "nudge_sent", "opened", "answered", "skipped", "commented")
        private val FEEDBACK_REASONS = listOf(
            "unclear_next", "unclear_result_losses", "too_long", "not_interesting", "no_time", "technical_problem", "other",
        )
        private val KNOWN_DROPOFF_CONTEXTS = TechnicalFailure.entries.map { it.value }.toSet() + setOf("none", "incomplete_attempt")

        internal fun quantile(values: List<Double>, probability: Double): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            if (sorted.size == 1) return sorted.first()
            val position = probability.coerceIn(0.0, 1.0) * (sorted.lastIndex)
            val lower = position.toInt()
            val upper = kotlin.math.ceil(position).toInt()
            if (lower == upper) return sorted[lower]
            return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
        }

        private fun normalizeDropoffContext(value: String?): String = value?.takeIf { it in KNOWN_DROPOFF_CONTEXTS } ?: "unknown"
    }

    private data class ActivationCounts(
        val steps: Map<String, Long>,
        val neverStarted: Long,
        val secondAfterFinish: Long,
        val secondAfterRegistration: Long,
    )
    private data class RetentionKey(val day: String, val status: String)
    private data class DropoffKey(val stage: String, val context: String)
    private data class PostBattleKey(val action: String, val status: String)
    private data class RecoveryTiming(val playerId: Long, val blockedAt: java.time.Instant, val completedAt: java.time.Instant?)
    private data class RecoveryBattleStart(
        val playerId: Long,
        val completionSessionId: java.util.UUID,
        val completedAt: java.time.Instant,
        val battleSessionId: java.util.UUID?,
        val battleAt: java.time.Instant?,
    )
    private data class NavigationKey(val category: String, val stage: String)
    private data class NavigationErrorEvent(
        val playerId: Long,
        val sessionId: java.util.UUID,
        val occurredAt: java.time.Instant,
        val key: NavigationKey,
    )
    private data class NavigationActionEvent(val playerId: Long, val sessionId: java.util.UUID, val occurredAt: java.time.Instant)
    private data class FrontBridgeEvent(
        val playerId: Long,
        val sessionId: java.util.UUID,
        val name: String,
        val occurredAt: java.time.Instant,
    )
}
