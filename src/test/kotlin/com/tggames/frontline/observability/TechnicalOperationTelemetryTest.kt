package com.tggames.frontline.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

class TechnicalOperationTelemetryTest {
    @Test
    fun `records successful battle latency and a send failure with bounded metric tags`() {
        val fixture = fixture("stages")
        val session = JourneySession(UUID.randomUUID(), internal = false)
        val success = fixture.telemetry.begin(TechnicalOperation.BATTLE, "personal", session, UUID.randomUUID(), 10)
        fixture.telemetry.success(success, TechnicalStage.ACCEPTED)
        fixture.clock.advanceMillis(125)
        fixture.telemetry.success(success, TechnicalStage.RESULT_COMMITTED)
        fixture.clock.advanceMillis(375)
        fixture.telemetry.success(success, TechnicalStage.RESULT_SENT, terminal = true)

        val failed = fixture.telemetry.begin(TechnicalOperation.BATTLE, "personal", session, UUID.randomUUID(), 10)
        fixture.telemetry.success(failed, TechnicalStage.ACCEPTED)
        fixture.clock.advanceMillis(25)
        fixture.telemetry.success(failed, TechnicalStage.RESULT_COMMITTED)
        fixture.telemetry.failure(failed, TechnicalStage.RESULT_SENT, TechnicalFailure.TELEGRAM_SEND)

        assertThat(stages(fixture.jdbc, success.id)).containsExactly(
            "accepted" to 0L,
            "result_committed" to 125L,
            "result_sent" to 500L,
        )
        assertThat(
            fixture.jdbc.sql("SELECT outcome FROM technical_operation_attempts WHERE attempt_id = :id")
                .param("id", success.id).query(String::class.java).single(),
        ).isEqualTo("success")
        assertThat(
            fixture.jdbc.sql("SELECT failure_class FROM technical_operation_attempts WHERE attempt_id = :id")
                .param("id", failed.id).query(String::class.java).single(),
        ).isEqualTo("telegram_send")
        assertThat(
            fixture.registry.find("frontline.technical.stage")
                .tags("operation", "battle", "mode", "personal", "stage", "result_sent", "result", "failure", "failure", "telegram_send", "cache", "none")
                .counter()?.count(),
        ).isEqualTo(1.0)
        assertThat(
            fixture.registry.find("frontline.technical.stage.duration")
                .tags("operation", "battle", "mode", "personal", "stage", "result_sent", "result", "success", "failure", "none", "cache", "none")
                .timer()?.count(),
        ).isEqualTo(1)
    }

    @Test
    fun `technical attempt survives rollback and restart becomes an explicit unknown outcome`() {
        val fixture = fixture("rollback")
        val outer = TransactionTemplate(fixture.transactionManager)
        lateinit var attempt: TechnicalAttempt

        assertThrows<IllegalStateException> {
            outer.executeWithoutResult {
                attempt = fixture.telemetry.begin(
                    TechnicalOperation.BATTLE,
                    "personal",
                    JourneySession(UUID.randomUUID(), internal = false),
                    UUID.randomUUID(),
                    10,
                )
                fixture.telemetry.success(attempt, TechnicalStage.ACCEPTED)
                throw IllegalStateException("game transaction rolled back")
            }
        }
        fixture.clock.advanceMillis(1)
        fixture.telemetry.markRestartedAttemptsBefore(fixture.clock.instant())

        val row = fixture.jdbc.sql(
            "SELECT outcome, failure_class, last_stage FROM technical_operation_attempts WHERE attempt_id = :id",
        ).param("id", attempt.id).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
        assertThat(row).isEqualTo(Triple("unknown", "restart", "abandoned"))
    }

    @Test
    fun `startup reconciliation does not abandon attempts created by another ready listener`() {
        val fixture = fixture("startup_cutoff")
        val attempt = fixture.telemetry.begin(
            TechnicalOperation.REPLAY,
            "personal",
            JourneySession(UUID.randomUUID(), internal = false),
            UUID.randomUUID(),
        )
        fixture.clock.advanceMillis(1)

        fixture.telemetry.markRestartedAttempts()

        assertThat(
            fixture.jdbc.sql("SELECT completed_at FROM technical_operation_attempts WHERE attempt_id = :id")
                .param("id", attempt.id)
                .query { rs, _ -> rs.getTimestamp(1) }
                .optional()
                .orElse(null),
        ).isNull()
    }

    @Test
    fun `duplicate stages do not double count and internal attempts stay out of product metrics`() {
        val fixture = fixture("dedupe_internal")
        val external = fixture.telemetry.begin(
            TechnicalOperation.REPLAY,
            "personal",
            JourneySession(UUID.randomUUID(), internal = false),
            UUID.randomUUID(),
        )
        fixture.telemetry.success(external, TechnicalStage.REQUESTED)
        fixture.telemetry.success(external, TechnicalStage.REQUESTED)
        val internal = fixture.telemetry.begin(
            TechnicalOperation.REPLAY,
            "personal",
            JourneySession(UUID.randomUUID(), internal = true),
            UUID.randomUUID(),
        )
        fixture.telemetry.success(internal, TechnicalStage.REQUESTED)

        assertThat(fixture.jdbc.sql("SELECT COUNT(*) FROM technical_operation_stages").query(Long::class.java).single()).isEqualTo(2)
        assertThat(
            fixture.registry.find("frontline.technical.stage")
                .tags("operation", "replay", "mode", "personal", "stage", "requested", "result", "success")
                .counters().sumOf { it.count() },
        ).isEqualTo(1.0)
    }

    @Test
    fun `stuck replay attempt is marked as a timeout`() {
        val fixture = fixture("timeout")
        val attempt = fixture.telemetry.begin(
            TechnicalOperation.REPLAY,
            "personal",
            JourneySession(UUID.randomUUID(), internal = false),
            UUID.randomUUID(),
        )
        fixture.telemetry.success(attempt, TechnicalStage.REQUESTED)
        fixture.clock.advanceMillis(15 * 60 * 1_000L + 1)

        fixture.telemetry.markTimedOutAttempts()

        val row = fixture.jdbc.sql(
            "SELECT outcome, failure_class, last_stage FROM technical_operation_attempts WHERE attempt_id = :id",
        ).param("id", attempt.id).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
        assertThat(row).isEqualTo(Triple("failure", "timeout", "abandoned"))
    }

    private fun fixture(name: String): Fixture {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:technical_$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        val jdbc = JdbcClient.create(dataSource)
        jdbc.sql(
            """
            CREATE TABLE technical_operation_attempts(
                attempt_id UUID PRIMARY KEY, operation VARCHAR(16) NOT NULL, mode VARCHAR(16) NOT NULL,
                session_id UUID, resource_id UUID, engine_version SMALLINT, experiment_variant VARCHAR(32),
                is_internal BOOLEAN NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                last_stage VARCHAR(32), last_stage_at TIMESTAMP WITH TIME ZONE, completed_at TIMESTAMP WITH TIME ZONE,
                outcome VARCHAR(16), failure_class VARCHAR(32), cache_status VARCHAR(16)
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE technical_operation_stages(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                attempt_id UUID NOT NULL, stage VARCHAR(32) NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                duration_ms BIGINT NOT NULL, result VARCHAR(16) NOT NULL, failure_class VARCHAR(32),
                cache_status VARCHAR(16) NOT NULL, UNIQUE(attempt_id, stage)
            )
            """.trimIndent(),
        ).update()
        val clock = MutableClock(Instant.parse("2026-10-08T12:00:00Z"))
        val registry = SimpleMeterRegistry()
        val transactionManager = DataSourceTransactionManager(dataSource)
        return Fixture(jdbc, registry, transactionManager, clock, TechnicalOperationTelemetry(jdbc, registry, transactionManager, clock))
    }

    private fun stages(jdbc: JdbcClient, attemptId: UUID): List<Pair<String, Long>> = jdbc.sql(
        "SELECT stage, duration_ms FROM technical_operation_stages WHERE attempt_id = :id ORDER BY id",
    ).param("id", attemptId).query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list()

    private data class Fixture(
        val jdbc: JdbcClient,
        val registry: SimpleMeterRegistry,
        val transactionManager: DataSourceTransactionManager,
        val clock: MutableClock,
        val telemetry: TechnicalOperationTelemetry,
    )

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advanceMillis(milliseconds: Long) {
            current = current.plusMillis(milliseconds)
        }
    }
}
