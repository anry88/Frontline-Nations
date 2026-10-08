package com.tggames.frontline.observability

import com.tggames.frontline.config.FrontlineProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class PlayerJourneyTest {
    @Test
    fun `records an ordered external first-battle path without Telegram identity`() {
        val fixture = fixture("full_path")

        fixture.journey.withinTelegramUpdate(100) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION, JourneyEventDetails(surface = "message"))
            fixture.journey.record(1, JourneyEventType.REGISTRATION_COMPLETED, JourneyEventDetails(surface = "telegram"))
            fixture.journey.record(1, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "start"))
            fixture.jdbc.sql("UPDATE players SET alliance_code = 'US' WHERE telegram_id = 1").update()
            fixture.journey.record(1, JourneyEventType.ONBOARDING_COUNTRY_SELECTED, JourneyEventDetails(referenceId = "RS"))
            fixture.journey.record(1, JourneyEventType.ONBOARDING_CTA_VIEWED, JourneyEventDetails(surface = "country_selected"))
            fixture.journey.record(1, JourneyEventType.ONBOARDING_CTA_CLICKED, JourneyEventDetails(surface = "battle_entry"))
            fixture.journey.record(1, JourneyEventType.ARMY_VIEWED, JourneyEventDetails(presetNo = 1, usedCp = 12))
            fixture.journey.record(1, JourneyEventType.ARMY_CHANGED, JourneyEventDetails(presetNo = 1, usedCp = 14))
            fixture.journey.record(1, JourneyEventType.BATTLE_OFFER_VIEWED, JourneyEventDetails(offerVersion = 1))
            fixture.journey.record(1, JourneyEventType.BATTLE_OFFER_SELECTED, JourneyEventDetails(offerVersion = 1, offerSlot = 0))
            fixture.journey.record(1, JourneyEventType.BATTLE_DEPLOYMENT_SELECTED, JourneyEventDetails(surface = "entry", entryId = "A"))
            fixture.journey.record(1, JourneyEventType.BATTLE_DEPLOYMENT_SELECTED, JourneyEventDetails(surface = "objective", entryId = "A", objectiveId = "O1"))
            fixture.journey.record(1, JourneyEventType.BATTLE_DEPLOYMENT_SELECTED, JourneyEventDetails(surface = "tactic", entryId = "A", objectiveId = "O1", tactic = "balanced"))
            fixture.journey.record(1, JourneyEventType.BATTLE_STARTED, JourneyEventDetails(battleId = UUID.fromString("00000000-0000-0000-0000-000000000001")))
            fixture.journey.record(1, JourneyEventType.BATTLE_FINISHED, JourneyEventDetails(result = "victory"))
        }

        val rows = fixture.jdbc.sql(
            "SELECT event_name, event_sequence, session_id, schema_version, is_internal FROM player_journey_events ORDER BY id",
        ).query { result, _ ->
            EventRow(
                result.getString("event_name"),
                result.getInt("event_sequence"),
                result.getObject("session_id", UUID::class.java),
                result.getInt("schema_version"),
                result.getBoolean("is_internal"),
            )
        }.list()

        assertThat(rows.map { it.name }).containsExactly(
            "session_started",
            "user_action",
            "registration_completed",
            "onboarding_country_viewed",
            "onboarding_country_selected",
            "onboarding_cta_viewed",
            "onboarding_cta_clicked",
            "army_viewed",
            "army_changed",
            "battle_offer_viewed",
            "battle_offer_selected",
            "battle_deployment_selected",
            "battle_deployment_selected",
            "battle_deployment_selected",
            "battle_started",
            "battle_finished",
        )
        assertThat(rows.map { it.sequence }).containsExactlyElementsOf(rows.indices.toList())
        assertThat(rows.map { it.sessionId }.distinct()).hasSize(1)
        assertThat(rows).allMatch { it.schemaVersion == PlayerJourney.SCHEMA_VERSION && !it.internal }
        assertThat(
            fixture.jdbc.sql("SELECT country_code FROM player_journey_events WHERE event_name = 'onboarding_country_selected'")
                .query(String::class.java).single(),
        ).isEqualTo("US")
        assertThat(columnNames(fixture.jdbc, "PLAYER_JOURNEY_EVENTS")).doesNotContain("PLAYER_TELEGRAM_ID", "TELEGRAM_ID")
    }

    @Test
    fun `starts a new session only after thirty minutes without a user event`() {
        val fixture = fixture("sessions")

        fixture.journey.withinTelegramUpdate(1) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
        }
        fixture.clock.advanceSeconds(29 * 60)
        fixture.journey.withinTelegramUpdate(2) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
        }
        fixture.clock.advanceSeconds(30 * 60)
        fixture.journey.withinTelegramUpdate(3) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
        }

        val sessions = fixture.jdbc.sql(
            "SELECT telegram_update_id, session_id FROM player_journey_events WHERE event_name = 'user_action' ORDER BY id",
        ).query { result, _ -> result.getLong("telegram_update_id") to result.getObject("session_id", UUID::class.java) }.list()
        assertThat(sessions[1].second).isEqualTo(sessions[0].second)
        assertThat(sessions[2].second).isNotEqualTo(sessions[1].second)
        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events WHERE event_name = 'session_started'").query(Long::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `marks configured owner accounts and preserves them for separate filtering`() {
        val fixture = fixture("internal", internalIds = listOf(2))
        fixture.journey.markConfiguredInternalAccounts()

        fixture.journey.withinTelegramUpdate(20) {
            fixture.journey.record(2, JourneyEventType.USER_ACTION)
        }
        fixture.journey.withinTelegramUpdate(21) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
        }

        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events WHERE NOT is_internal").query(Long::class.java).single()).isEqualTo(2)
        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events WHERE is_internal").query(Long::class.java).single()).isEqualTo(2)
        assertThat(fixture.jdbc.sql("SELECT is_internal FROM analytics_players WHERE player_telegram_id = 2").query(Boolean::class.java).single()).isTrue()
    }

    @Test
    fun `rolls business events back with the surrounding transaction`() {
        val fixture = fixture("rollback")
        val transaction = TransactionTemplate(DataSourceTransactionManager(fixture.dataSource))

        assertThrows<IllegalStateException> {
            transaction.executeWithoutResult {
                fixture.journey.withinTelegramUpdate(30) {
                    fixture.journey.record(1, JourneyEventType.DAILY_CLAIMED)
                    throw IllegalStateException("business mutation failed")
                }
            }
        }

        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events").query(Long::class.java).single()).isZero()
    }

    @Test
    fun `rejects a repeated update sequence instead of duplicating events`() {
        val fixture = fixture("deduplication")
        fixture.journey.withinTelegramUpdate(40) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
        }

        assertThrows<Exception> {
            fixture.journey.withinTelegramUpdate(40) {
                fixture.journey.record(1, JourneyEventType.USER_ACTION)
            }
        }

        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events").query(Long::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `records a deferred delivery event only after the returned action runs`() {
        val fixture = fixture("deferred")
        lateinit var delivered: () -> Unit

        fixture.journey.withinTelegramUpdate(50) {
            fixture.journey.record(1, JourneyEventType.USER_ACTION)
            delivered = fixture.journey.deferred(
                1,
                JourneyEventType.FRONT_BRIDGE_SHOWN,
                JourneyEventDetails(surface = "battle_result", result = "contribute"),
            )
        }

        assertThat(
            fixture.jdbc.sql("SELECT COUNT(*) FROM player_journey_events WHERE event_name = 'front_bridge_shown'")
                .query(Long::class.java).single(),
        ).isZero()
        delivered()
        assertThat(
            fixture.jdbc.sql("SELECT event_sequence FROM player_journey_events WHERE event_name = 'front_bridge_shown'")
                .query(Int::class.java).single(),
        ).isEqualTo(2)
    }

    private fun fixture(name: String, internalIds: List<Long> = emptyList()): Fixture {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:journey_$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        val jdbc = JdbcClient.create(dataSource)
        listOf(
            "CREATE TABLE players(telegram_id BIGINT PRIMARY KEY, registration_source VARCHAR(16) NOT NULL, registration_referral VARCHAR(64), language VARCHAR(8) NOT NULL, alliance_code VARCHAR(8))",
            "CREATE TABLE analytics_players(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, player_telegram_id BIGINT NOT NULL UNIQUE, is_internal BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE player_journey_events(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, analytics_player_id BIGINT NOT NULL, session_id UUID NOT NULL, telegram_update_id BIGINT NOT NULL, event_sequence SMALLINT NOT NULL, event_name VARCHAR(64) NOT NULL, schema_version SMALLINT NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, is_internal BOOLEAN NOT NULL, registration_source VARCHAR(16) NOT NULL, registration_referral VARCHAR(64), locale VARCHAR(8) NOT NULL, country_code VARCHAR(8), surface VARCHAR(32), result VARCHAR(32), reason VARCHAR(64), variant VARCHAR(32), reference_id VARCHAR(64), battle_id UUID, offer_version BIGINT, offer_slot SMALLINT, preset_no SMALLINT, used_cp INTEGER, entry_id VARCHAR(32), objective_id VARCHAR(32), tactic VARCHAR(32), unit_code VARCHAR(32), quantity INTEGER, UNIQUE(telegram_update_id, event_sequence))",
            "INSERT INTO players VALUES (1, 'referral', 'gramads', 'en', 'RS')",
            "INSERT INTO players VALUES (2, 'telegram', NULL, 'ru', 'US')",
            "INSERT INTO analytics_players(player_telegram_id) VALUES (1)",
            "INSERT INTO analytics_players(player_telegram_id) VALUES (2)",
        ).forEach { jdbc.sql(it).update() }
        val clock = MutableClock(Instant.parse("2026-10-08T12:00:00Z"))
        val properties = FrontlineProperties(
            analytics = FrontlineProperties.Analytics(
                internalTelegramIds = internalIds,
                sessionTimeoutMinutes = 30,
                eventRetentionDays = 400,
            ),
        )
        return Fixture(dataSource, jdbc, clock, PlayerJourney(jdbc, properties, clock))
    }

    private fun columnNames(jdbc: JdbcClient, table: String): List<String> = jdbc.sql(
        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = :table ORDER BY ORDINAL_POSITION",
    ).param("table", table).query(String::class.java).list()

    private data class Fixture(
        val dataSource: DriverManagerDataSource,
        val jdbc: JdbcClient,
        val clock: MutableClock,
        val journey: PlayerJourney,
    )

    private data class EventRow(
        val name: String,
        val sequence: Int,
        val sessionId: UUID,
        val schemaVersion: Int,
        val internal: Boolean,
    )

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
