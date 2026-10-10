package com.tggames.frontline.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

class ProductAnalyticsMetricsTest {
    @Test
    fun `publishes external activation retention and dropoff baseline from an artificial cohort`() {
        val jdbc = JdbcClient.create(DriverManagerDataSource("jdbc:h2:mem:product_metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"))
        createTables(jdbc)
        insertArtificialCohort(jdbc)
        val registry = SimpleMeterRegistry()

        ProductAnalyticsMetrics(registry, jdbc).refresh()

        assertGauge(registry, "frontline.product.activation.players", 2.0, "step", "registration", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "registration", "variant", "legacy")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "registration", "variant", "unassigned")
        assertGauge(registry, "frontline.product.activation.players", 2.0, "step", "country_selected", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "country_selected", "variant", "legacy")
        assertGauge(registry, "frontline.product.activation.players", 0.0, "step", "country_selected", "variant", "unassigned")
        assertGauge(registry, "frontline.product.activation.players", 2.0, "step", "offer_viewed", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 0.0, "step", "offer_viewed", "variant", "legacy")
        assertGauge(registry, "frontline.product.activation.players", 2.0, "step", "deployment_completed", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 0.0, "step", "deployment_completed", "variant", "legacy")
        assertGauge(registry, "frontline.product.activation.players", 2.0, "step", "first_battle_started", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "first_battle_finished", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "result_sent", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.players", 1.0, "step", "second_battle_started", "variant", "guided_v1")
        assertGauge(registry, "frontline.product.activation.second.battle", 2.0, "window", "never_started")
        assertGauge(registry, "frontline.product.activation.second.battle", 1.0, "window", "within_24h_after_first_finish")
        assertGauge(registry, "frontline.product.activation.second.battle", 1.0, "window", "within_24h_after_registration")
        assertGauge(registry, "frontline.product.activation.ttfb", 90.0, "quantile", "p50", "unit", "seconds")
        assertGauge(registry, "frontline.product.activation.ttfb", 114.0, "quantile", "p90", "unit", "seconds")
        assertGauge(registry, "frontline.product.retention.players", 3.0, "day", "d1", "status", "matured")
        assertGauge(registry, "frontline.product.retention.players", 2.0, "day", "d1", "status", "retained")
        assertGauge(registry, "frontline.product.retention.players", 1.0, "day", "d3", "status", "matured")
        assertGauge(registry, "frontline.product.retention.players", 0.0, "day", "d7", "status", "matured")
        assertGauge(registry, "frontline.product.dropoff.players", 1.0, "stage", "registration", "context", "none")
        assertGauge(registry, "frontline.product.dropoff.players", 1.0, "stage", "first_battle_started", "context", "engine_failure")
        assertGauge(registry, "frontline.product.post.battle.action", 2.0, "action", "next_battle", "status", "sent")
        assertGauge(registry, "frontline.product.post.battle.action", 1.0, "action", "next_battle", "status", "clicked")
        assertGauge(registry, "frontline.product.post.battle.action", 1.0, "action", "next_battle", "status", "completed")
        assertGauge(registry, "frontline.product.post.battle.action", 1.0, "action", "restore_group", "status", "sent")
        assertGauge(registry, "frontline.product.post.battle.action", 0.0, "action", "choose_group", "status", "sent")
        assertGauge(registry, "frontline.product.battle.result", 3.0, "status", "sent")
        assertGauge(registry, "frontline.product.battle.result", 1.0, "status", "details_opened")
        assertGauge(registry, "frontline.product.army.recovery.players", 2.0, "status", "blocked")
        assertGauge(registry, "frontline.product.army.recovery.players", 1.0, "status", "completed")
        assertGauge(registry, "frontline.product.army.recovery.players", 1.0, "status", "next_battle_same_session")
        assertGauge(registry, "frontline.product.army.recovery.players", 1.0, "status", "next_battle_24h")
        assertGauge(registry, "frontline.product.army.recovery.time", 600.0, "quantile", "p50", "unit", "seconds")
        assertGauge(registry, "frontline.product.navigation.errors", 2.0, "category", "stale_callback", "stage", "army_recovery")
        assertGauge(registry, "frontline.product.navigation.recovery.players", 1.0, "status", "errored")
        assertGauge(registry, "frontline.product.navigation.recovery.players", 1.0, "status", "recovered_same_session")
        assertGauge(registry, "frontline.product.navigation.recovery.players", 1.0, "status", "repeat_error")
        assertGauge(registry, "frontline.product.front.bridge.players", 2.0, "status", "shown")
        assertGauge(registry, "frontline.product.front.bridge.players", 1.0, "status", "clicked")
        assertGauge(registry, "frontline.product.front.bridge.players", 1.0, "status", "contributed")
        assertGauge(registry, "frontline.product.front.bridge.players", 1.0, "status", "withdrawn")
        assertGauge(registry, "frontline.product.front.bridge.players", 1.0, "status", "returned_session")
        assertGauge(registry, "frontline.product.front.bridge.players", 1.0, "status", "reservation_blocked")
        assertGauge(registry, "frontline.product.feedback.players", 2.0, "status", "inline_offered")
        assertGauge(registry, "frontline.product.feedback.players", 1.0, "status", "nudge_sent")
        assertGauge(registry, "frontline.product.feedback.players", 1.0, "status", "answered")
        assertGauge(registry, "frontline.product.feedback.players", 1.0, "status", "commented")
        assertGauge(registry, "frontline.product.feedback.reason", 1.0, "reason", "too_long")
        assertGauge(registry, "frontline.product.feedback.reason", 0.0, "reason", "technical_problem")
    }

    @Test
    fun `quantiles handle empty and small samples deterministically`() {
        assertThat(ProductAnalyticsMetrics.quantile(emptyList(), 0.5)).isZero()
        assertThat(ProductAnalyticsMetrics.quantile(listOf(42.0), 0.9)).isEqualTo(42.0)
        assertThat(ProductAnalyticsMetrics.quantile(listOf(0.0, 10.0, 20.0), 0.5)).isEqualTo(10.0)
    }

    private fun createTables(jdbc: JdbcClient) {
        jdbc.sql(
            """
            CREATE TABLE analytics_player_activation_all(
                analytics_player_id BIGINT PRIMARY KEY, is_internal BOOLEAN NOT NULL,
                onboarding_variant VARCHAR(32),
                country_selected_at TIMESTAMP, offer_viewed_at TIMESTAMP, deployment_completed_at TIMESTAMP,
                first_battle_started_at TIMESTAMP, first_battle_finished_at TIMESTAMP, first_result_sent_at TIMESTAMP,
                second_battle_started_at TIMESTAMP, second_battle_within_24h_after_finish BOOLEAN NOT NULL,
                second_battle_within_24h_after_registration BOOLEAN NOT NULL, ttfb_ms BIGINT
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE front_bridge_offers(
                player_telegram_id BIGINT PRIMARY KEY, shown_at TIMESTAMP WITH TIME ZONE,
                clicked_at TIMESTAMP WITH TIME ZONE
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE analytics_retention_cohorts_all(
                analytics_player_id BIGINT PRIMARY KEY, is_internal BOOLEAN NOT NULL,
                d1_matured BOOLEAN NOT NULL, d1_retained BOOLEAN NOT NULL,
                d3_matured BOOLEAN NOT NULL, d3_retained BOOLEAN NOT NULL,
                d7_matured BOOLEAN NOT NULL, d7_retained BOOLEAN NOT NULL
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE analytics_dropoff_report_all(
                analytics_player_id BIGINT PRIMARY KEY, is_internal BOOLEAN NOT NULL,
                last_activation_stage VARCHAR(32) NOT NULL, observed_technical_context VARCHAR(32) NOT NULL
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE player_journey_events(
                analytics_player_id BIGINT, session_id UUID, event_name VARCHAR(64), surface VARCHAR(32), reason VARCHAR(64),
                occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, is_internal BOOLEAN NOT NULL
            )
            """.trimIndent(),
        ).update()
        jdbc.sql("CREATE TABLE analytics_players(player_telegram_id BIGINT PRIMARY KEY, is_internal BOOLEAN NOT NULL)").update()
        jdbc.sql(
            """
            CREATE TABLE battle_next_actions(
                battle_id VARCHAR(36) PRIMARY KEY, player_telegram_id BIGINT NOT NULL, action VARCHAR(24) NOT NULL,
                sent_at TIMESTAMP WITH TIME ZONE, clicked_at TIMESTAMP WITH TIME ZONE,
                completed_at TIMESTAMP WITH TIME ZONE, details_opened_at TIMESTAMP WITH TIME ZONE
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE analytics_feedback_responses(
                inline_offered_at TIMESTAMP WITH TIME ZONE, nudge_sent_at TIMESTAMP WITH TIME ZONE,
                opened_at TIMESTAMP WITH TIME ZONE, answered_at TIMESTAMP WITH TIME ZONE,
                skipped_at TIMESTAMP WITH TIME ZONE, commented BOOLEAN, response_reason VARCHAR(32)
            )
            """.trimIndent(),
        ).update()
    }

    private fun insertArtificialCohort(jdbc: JdbcClient) {
        listOf(
            "INSERT INTO analytics_player_activation_all VALUES (1, FALSE, 'guided_v1', NOW(), NOW(), NOW(), NOW(), NOW(), NOW(), NOW(), TRUE, TRUE, 60000)",
            "INSERT INTO analytics_player_activation_all VALUES (2, FALSE, 'legacy', NOW(), NULL, NULL, NULL, NULL, NULL, NULL, FALSE, FALSE, NULL)",
            "INSERT INTO analytics_player_activation_all VALUES (3, FALSE, 'guided_v1', NOW(), NOW(), NOW(), NOW(), NULL, NULL, NULL, FALSE, FALSE, 120000)",
            "INSERT INTO analytics_player_activation_all VALUES (4, TRUE, 'guided_v1', NOW(), NOW(), NOW(), NOW(), NOW(), NOW(), NOW(), TRUE, TRUE, 1000)",
            "INSERT INTO analytics_player_activation_all VALUES (5, FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, FALSE, FALSE, NULL)",
            "INSERT INTO analytics_retention_cohorts_all VALUES (1, FALSE, TRUE, TRUE, TRUE, FALSE, FALSE, FALSE)",
            "INSERT INTO analytics_retention_cohorts_all VALUES (2, FALSE, TRUE, FALSE, FALSE, FALSE, FALSE, FALSE)",
            "INSERT INTO analytics_retention_cohorts_all VALUES (3, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE)",
            "INSERT INTO analytics_retention_cohorts_all VALUES (4, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE, TRUE)",
            "INSERT INTO analytics_retention_cohorts_all VALUES (5, FALSE, TRUE, TRUE, FALSE, FALSE, FALSE, FALSE)",
            "INSERT INTO analytics_dropoff_report_all VALUES (1, FALSE, 'result_sent', 'none')",
            "INSERT INTO analytics_dropoff_report_all VALUES (2, FALSE, 'country_selected', 'none')",
            "INSERT INTO analytics_dropoff_report_all VALUES (3, FALSE, 'first_battle_started', 'engine_failure')",
            "INSERT INTO analytics_dropoff_report_all VALUES (4, TRUE, 'result_sent', 'none')",
            "INSERT INTO analytics_dropoff_report_all VALUES (5, FALSE, 'registration', 'none')",
            "INSERT INTO player_journey_events(occurred_at,is_internal) VALUES (TIMESTAMP WITH TIME ZONE '2026-10-01 00:00:00+00', FALSE)",
            "INSERT INTO player_journey_events(occurred_at,is_internal) VALUES (TIMESTAMP WITH TIME ZONE '2026-10-08 00:00:00+00', FALSE)",
            "INSERT INTO player_journey_events(occurred_at,is_internal) VALUES (TIMESTAMP WITH TIME ZONE '2026-09-01 00:00:00+00', TRUE)",
            "INSERT INTO player_journey_events(analytics_player_id,session_id,event_name,occurred_at,is_internal) VALUES (201, '00000000-0000-0000-0000-000000000201', 'army_recovery_blocked', TIMESTAMP WITH TIME ZONE '2026-10-07 10:00:00+00', FALSE)",
            "INSERT INTO player_journey_events(analytics_player_id,session_id,event_name,occurred_at,is_internal) VALUES (201, '00000000-0000-0000-0000-000000000201', 'army_recovery_completed', TIMESTAMP WITH TIME ZONE '2026-10-07 10:10:00+00', FALSE)",
            "INSERT INTO player_journey_events(analytics_player_id,session_id,event_name,occurred_at,is_internal) VALUES (201, '00000000-0000-0000-0000-000000000201', 'battle_started', TIMESTAMP WITH TIME ZONE '2026-10-07 10:20:00+00', FALSE)",
            "INSERT INTO player_journey_events(analytics_player_id,session_id,event_name,occurred_at,is_internal) VALUES (202, '00000000-0000-0000-0000-000000000202', 'army_recovery_blocked', TIMESTAMP WITH TIME ZONE '2026-10-07 11:00:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (301, '00000000-0000-0000-0000-000000000301', 'navigation_error', 'stale_callback', 'army_recovery', TIMESTAMP WITH TIME ZONE '2026-10-07 12:00:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (301, '00000000-0000-0000-0000-000000000301', 'navigation_error', 'stale_callback', 'army_recovery', TIMESTAMP WITH TIME ZONE '2026-10-07 12:01:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (301, '00000000-0000-0000-0000-000000000301', 'army_viewed', 'army', NULL, TIMESTAMP WITH TIME ZONE '2026-10-07 12:02:00+00', FALSE)",
            "INSERT INTO analytics_players VALUES (101, FALSE)",
            "INSERT INTO analytics_players VALUES (102, FALSE)",
            "INSERT INTO analytics_players VALUES (103, TRUE)",
            "INSERT INTO battle_next_actions VALUES ('00000000-0000-0000-0000-000000000101', 101, 'next_battle', NOW(), NOW(), NOW(), NOW())",
            "INSERT INTO battle_next_actions VALUES ('00000000-0000-0000-0000-000000000102', 101, 'next_battle', NOW(), NULL, NULL, NULL)",
            "INSERT INTO battle_next_actions VALUES ('00000000-0000-0000-0000-000000000103', 102, 'restore_group', NOW(), NOW(), NULL, NULL)",
            "INSERT INTO battle_next_actions VALUES ('00000000-0000-0000-0000-000000000104', 103, 'choose_group', NOW(), NOW(), NOW(), NOW())",
            "INSERT INTO front_bridge_offers VALUES (101, TIMESTAMP WITH TIME ZONE '2026-10-07 13:00:00+00', TIMESTAMP WITH TIME ZONE '2026-10-07 13:01:00+00')",
            "INSERT INTO front_bridge_offers VALUES (102, TIMESTAMP WITH TIME ZONE '2026-10-07 14:00:00+00', NULL)",
            "INSERT INTO front_bridge_offers VALUES (103, TIMESTAMP WITH TIME ZONE '2026-10-07 15:00:00+00', TIMESTAMP WITH TIME ZONE '2026-10-07 15:01:00+00')",
            "INSERT INTO analytics_feedback_responses VALUES (NOW(), NOW(), NOW(), NOW(), NULL, TRUE, 'too_long')",
            "INSERT INTO analytics_feedback_responses VALUES (NOW(), NULL, NULL, NULL, NOW(), FALSE, NULL)",
            "INSERT INTO player_journey_events VALUES (101, '00000000-0000-0000-0000-000000000401', 'front_bridge_shown', 'battle_result', NULL, TIMESTAMP WITH TIME ZONE '2026-10-07 13:00:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (101, '00000000-0000-0000-0000-000000000401', 'contribution_committed', 'front', NULL, TIMESTAMP WITH TIME ZONE '2026-10-07 13:02:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (101, '00000000-0000-0000-0000-000000000401', 'contribution_withdrawn', 'front', NULL, TIMESTAMP WITH TIME ZONE '2026-10-07 13:03:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (101, '00000000-0000-0000-0000-000000000402', 'user_action', 'message', NULL, TIMESTAMP WITH TIME ZONE '2026-10-08 13:00:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (101, '00000000-0000-0000-0000-000000000402', 'personal_battle_blocked_by_reservation', 'battle_menu', 'front_reservation', TIMESTAMP WITH TIME ZONE '2026-10-08 13:01:00+00', FALSE)",
            "INSERT INTO player_journey_events VALUES (102, '00000000-0000-0000-0000-000000000403', 'front_bridge_shown', 'battle_result', NULL, TIMESTAMP WITH TIME ZONE '2026-10-07 14:00:00+00', FALSE)",
        ).forEach { jdbc.sql(it).update() }
    }

    private fun assertGauge(registry: SimpleMeterRegistry, name: String, expected: Double, vararg tags: String) {
        assertThat(registry.find(name).tags(*tags).gauge()?.value()).isEqualTo(expected)
    }
}
