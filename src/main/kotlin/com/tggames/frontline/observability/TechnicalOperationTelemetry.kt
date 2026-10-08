package com.tggames.frontline.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TechnicalOperation(val value: String) {
    BATTLE("battle"),
    REPLAY("replay"),
}

enum class TechnicalStage(val value: String) {
    ACCEPTED("accepted"),
    RESULT_COMMITTED("result_committed"),
    RESULT_SENT("result_sent"),
    REQUESTED("requested"),
    QUEUED("queued"),
    RENDER_STARTED("render_started"),
    READY("ready"),
    DELIVERED("delivered"),
    ABANDONED("abandoned"),
}

enum class TechnicalFailure(val value: String) {
    BUSINESS_STALE("business_stale"),
    BUSINESS_RULE("business_rule"),
    TELEGRAM_SEND("telegram_send"),
    TIMEOUT("timeout"),
    ENGINE_FAILURE("engine_failure"),
    RENDER_FAILURE("render_failure"),
    PERSISTENCE_FAILURE("persistence_failure"),
    RESTART("restart"),
    UNKNOWN("unknown"),
}

enum class TechnicalCache(val value: String) {
    NONE("none"),
    COLD("cold"),
    HIT("hit"),
}

data class TechnicalAttempt(
    val id: UUID,
    val operation: TechnicalOperation,
    val mode: String,
)

@Component
class TechnicalOperationTelemetry(
    private val jdbc: JdbcClient,
    private val registry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val applicationStartedAt = clock.instant()
    private val requiresNew = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun begin(
        operation: TechnicalOperation,
        mode: String,
        session: JourneySession?,
        resourceId: UUID?,
        engineVersion: Int? = null,
        experimentVariant: String? = null,
        isInternal: Boolean? = null,
    ): TechnicalAttempt {
        val attempt = TechnicalAttempt(UUID.randomUUID(), operation, bounded(mode, 16) ?: "unknown")
        safeWrite("start", attempt.id) {
            val now = clock.instant()
            jdbc.sql(
                """
                INSERT INTO technical_operation_attempts(
                    attempt_id, operation, mode, session_id, resource_id, engine_version,
                    experiment_variant, is_internal, started_at
                ) VALUES (
                    :attemptId, :operation, :mode, :sessionId, :resourceId, :engineVersion,
                    :variant, :internal, :startedAt
                )
                """.trimIndent(),
            ).param("attemptId", attempt.id)
                .param("operation", operation.value)
                .param("mode", attempt.mode)
                .param("sessionId", session?.id)
                .param("resourceId", resourceId)
                .param("engineVersion", engineVersion)
                .param("variant", bounded(experimentVariant, 32))
                .param("internal", isInternal ?: session?.internal ?: false)
                .param("startedAt", Timestamp.from(now))
                .update()
        }
        return attempt
    }

    fun success(attempt: TechnicalAttempt, stage: TechnicalStage, cache: TechnicalCache = TechnicalCache.NONE, terminal: Boolean = false) {
        record(attempt, stage, "success", null, cache, terminal)
    }

    fun failure(attempt: TechnicalAttempt, stage: TechnicalStage, failure: TechnicalFailure, cache: TechnicalCache = TechnicalCache.NONE) {
        record(attempt, stage, "failure", failure, cache, terminal = true)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun markRestartedAttempts() {
        markRestartedAttemptsBefore(applicationStartedAt)
    }

    internal fun markRestartedAttemptsBefore(cutoff: Instant) {
        incompleteAttemptsBefore(cutoff).forEach { attempt ->
            record(attempt, TechnicalStage.ABANDONED, "unknown", TechnicalFailure.RESTART, TechnicalCache.NONE, terminal = true)
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    fun markTimedOutAttempts() {
        incompleteAttemptsBefore(clock.instant().minus(Duration.ofMinutes(15))).forEach { attempt ->
            record(attempt, TechnicalStage.ABANDONED, "failure", TechnicalFailure.TIMEOUT, TechnicalCache.NONE, terminal = true)
        }
    }

    private fun record(
        attempt: TechnicalAttempt,
        stage: TechnicalStage,
        result: String,
        failure: TechnicalFailure?,
        cache: TechnicalCache,
        terminal: Boolean,
    ) {
        var measurement: Measurement? = null
        safeWrite(stage.value, attempt.id) {
            val now = clock.instant()
            val stored = jdbc.sql(
                """
                SELECT operation, mode, is_internal, started_at
                  FROM technical_operation_attempts
                 WHERE attempt_id = :attemptId
                """.trimIndent(),
            ).param("attemptId", attempt.id)
                .query { rs, _ ->
                    StoredAttempt(
                        operation = rs.getString("operation"),
                        mode = rs.getString("mode"),
                        internal = rs.getBoolean("is_internal"),
                        startedAt = rs.getTimestamp("started_at").toInstant(),
                    )
                }.optional().orElse(null) ?: return@safeWrite
            val durationMillis = Duration.between(stored.startedAt, now).toMillis().coerceAtLeast(0)
            try {
                jdbc.sql(
                    """
                    INSERT INTO technical_operation_stages(
                        attempt_id, stage, occurred_at, duration_ms, result, failure_class, cache_status
                    ) VALUES (
                        :attemptId, :stage, :occurredAt, :durationMs, :result, :failureClass, :cacheStatus
                    )
                    """.trimIndent(),
                ).param("attemptId", attempt.id)
                    .param("stage", stage.value)
                    .param("occurredAt", Timestamp.from(now))
                    .param("durationMs", durationMillis)
                    .param("result", result)
                    .param("failureClass", failure?.value)
                    .param("cacheStatus", cache.value)
                    .update()
            } catch (_: DuplicateKeyException) {
                return@safeWrite
            }
            jdbc.sql(
                """
                UPDATE technical_operation_attempts
                   SET last_stage = :stage,
                       last_stage_at = :occurredAt,
                       completed_at = CASE WHEN :terminal THEN :occurredAt ELSE completed_at END,
                       outcome = CASE WHEN :terminal THEN :result ELSE outcome END,
                       failure_class = CASE WHEN :terminal THEN :failureClass ELSE failure_class END,
                       cache_status = CASE WHEN :cacheStatus = 'none' THEN cache_status ELSE :cacheStatus END
                 WHERE attempt_id = :attemptId
                """.trimIndent(),
            ).param("stage", stage.value)
                .param("occurredAt", Timestamp.from(now))
                .param("terminal", terminal)
                .param("result", result)
                .param("failureClass", failure?.value)
                .param("cacheStatus", cache.value)
                .param("attemptId", attempt.id)
                .update()
            measurement = Measurement(stored, durationMillis)
        }
        measurement?.takeUnless { it.attempt.internal }?.let { value ->
            val tags = arrayOf(
                "operation", value.attempt.operation,
                "mode", value.attempt.mode,
                "stage", stage.value,
                "result", result,
                "failure", failure?.value ?: "none",
                "cache", cache.value,
            )
            registry.counter("frontline.technical.stage", *tags).increment()
            Timer.builder("frontline.technical.stage.duration")
                .tags(*tags)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                    Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1),
                    Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
                )
                .register(registry)
                .record(Duration.ofMillis(value.durationMillis))
        }
    }

    private fun incompleteAttemptsBefore(cutoff: Instant): List<TechnicalAttempt> = runCatching {
        requiresNew.execute {
            jdbc.sql(
                """
                SELECT attempt_id, operation, mode
                  FROM technical_operation_attempts
                 WHERE completed_at IS NULL AND started_at < :cutoff
                """.trimIndent(),
            ).param("cutoff", Timestamp.from(cutoff))
                .query { rs, _ ->
                    TechnicalAttempt(
                        id = rs.getObject("attempt_id", UUID::class.java),
                        operation = TechnicalOperation.entries.first { it.value == rs.getString("operation") },
                        mode = rs.getString("mode"),
                    )
                }.list()
        } ?: emptyList()
    }.onFailure { logger.warn("Could not find incomplete technical attempts", it) }.getOrDefault(emptyList())

    private fun safeWrite(action: String, attemptId: UUID, write: () -> Unit) {
        runCatching { requiresNew.executeWithoutResult { write() } }
            .onFailure { logger.warn("Could not {} technical attempt {}", action, attemptId, it) }
    }

    private fun bounded(value: String?, length: Int): String? = value?.take(length)

    private data class StoredAttempt(val operation: String, val mode: String, val internal: Boolean, val startedAt: Instant)
    private data class Measurement(val attempt: StoredAttempt, val durationMillis: Long)
}
