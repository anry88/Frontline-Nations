package com.tggames.frontline.replay

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.observability.PlayerJourney
import com.tggames.frontline.observability.JourneySession
import com.tggames.frontline.observability.TechnicalAttempt
import com.tggames.frontline.observability.TechnicalCache
import com.tggames.frontline.observability.TechnicalFailure
import com.tggames.frontline.observability.TechnicalOperation
import com.tggames.frontline.observability.TechnicalOperationTelemetry
import com.tggames.frontline.observability.TechnicalStage
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class ReplayDeliveryService(
    private val jdbc: JdbcClient,
    private val replays: ReplayService,
    private val telegram: TelegramClient,
    private val telemetry: TechnicalOperationTelemetry,
    private val journey: PlayerJourney,
    private val properties: FrontlineProperties,
    @param:Qualifier("replayWorkerExecutor") private val executor: TaskExecutor,
    transactionManager: PlatformTransactionManager,
    registry: MeterRegistry,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workerRunning = AtomicBoolean(false)
    private val acknowledgements = ConcurrentLinkedQueue<ReplayAcknowledgement>()
    private val pendingGauge = AtomicInteger(0)
    private val activeGauge = AtomicInteger(0)
    private val workerTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val insertTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
    }

    init {
        registry.gauge("frontline.replay.queue.pending", pendingGauge)
        registry.gauge("frontline.replay.queue.active", activeGauge)
    }

    fun requestPersonal(playerId: Long, chatId: Long, language: GameLanguage, battleId: UUID) =
        request(ReplayKind.PERSONAL, playerId, chatId, language, battleId, null)

    fun requestWeekly(
        playerId: Long,
        chatId: Long,
        language: GameLanguage,
        matchupId: UUID,
        allianceCode: String?,
    ) = request(ReplayKind.WEEKLY, playerId, chatId, language, matchupId, allianceCode)

    private fun request(
        kind: ReplayKind,
        playerId: Long,
        chatId: Long,
        language: GameLanguage,
        resourceId: UUID,
        allianceCode: String?,
    ) {
        val session = journey.currentSession(playerId)
        val inserted = try {
            insertTransaction.execute {
                jdbc.sql(
                    """
                    INSERT INTO replay_delivery_jobs(
                        id, kind, resource_id, player_telegram_id, chat_id, language,
                        alliance_code, is_internal, status, next_attempt_at, requested_at, updated_at
                    ) VALUES (
                        :id, :kind, :resourceId, :playerId, :chatId, :language,
                        :allianceCode, :internal, 'initializing', :now, :now, :now
                    )
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                    .param("kind", kind.path)
                    .param("resourceId", resourceId)
                    .param("playerId", playerId)
                    .param("chatId", chatId)
                    .param("language", language.code)
                    .param("allianceCode", allianceCode)
                    .param("internal", session?.internal ?: false)
                    .param("now", Timestamp.from(clock.instant()))
                    .update()
                true
            } ?: false
        } catch (_: DuplicateKeyException) {
            false
        }

        val existing = findJob(kind, resourceId, playerId, lock = true)
            ?: error("Replay job disappeared after request")
        val acknowledgementKey = if (inserted) {
            startAttempt(existing, language, chatId, allianceCode, session, ReplayJobStatus.QUEUED)
            "replay_queued"
        } else {
            when (existing.status) {
                ReplayJobStatus.INITIALIZING, ReplayJobStatus.QUEUED, ReplayJobStatus.RENDERING -> {
                    updateDestination(existing.id, chatId, language, allianceCode)
                    "replay_already_preparing"
                }
                ReplayJobStatus.READY, ReplayJobStatus.DELIVERED -> {
                    startAttempt(existing, language, chatId, allianceCode, session, ReplayJobStatus.READY)
                    "replay_ready_resending"
                }
                ReplayJobStatus.FAILED -> {
                    startAttempt(existing, language, chatId, allianceCode, session, ReplayJobStatus.QUEUED)
                    "replay_retrying"
                }
            }
        }

        afterCommit {
            acknowledgements.add(ReplayAcknowledgement(chatId, language, acknowledgementKey))
            wake()
        }
    }

    private fun startAttempt(
        job: ReplayJob,
        language: GameLanguage,
        chatId: Long,
        allianceCode: String?,
        session: JourneySession?,
        targetStatus: ReplayJobStatus,
    ) {
        val attempt = newAttempt(job.kind, job.resourceId, session)
        jdbc.sql(
            """
            UPDATE replay_delivery_jobs
               SET chat_id = :chatId,
                   language = :language,
                   alliance_code = :allianceCode,
                   is_internal = :internal,
                   status = :status,
                   attempt_id = :attemptId,
                   delivery_attempts = 0,
                   last_error = NULL,
                   next_attempt_at = :now,
                   requested_at = :now,
                   updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        ).param("chatId", chatId)
            .param("language", language.code)
            .param("allianceCode", allianceCode)
            .param("internal", session?.internal ?: false)
            .param("status", targetStatus.value)
            .param("attemptId", attempt.id)
            .param("now", Timestamp.from(clock.instant()))
            .param("id", job.id)
            .update()
    }

    private fun updateDestination(jobId: UUID, chatId: Long, language: GameLanguage, allianceCode: String?) {
        jdbc.sql(
            """
            UPDATE replay_delivery_jobs
               SET chat_id = :chatId, language = :language, alliance_code = :allianceCode, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        ).param("chatId", chatId)
            .param("language", language.code)
            .param("allianceCode", allianceCode)
            .param("now", Timestamp.from(clock.instant()))
            .param("id", jobId)
            .update()
    }

    private fun newAttempt(kind: ReplayKind, resourceId: UUID, session: JourneySession?): TechnicalAttempt =
        telemetry.begin(
            TechnicalOperation.REPLAY,
            mode = kind.path,
            session = session,
            resourceId = resourceId,
        ).also { attempt ->
            telemetry.success(attempt, TechnicalStage.REQUESTED)
            telemetry.success(attempt, TechnicalStage.QUEUED)
        }

    private fun newBackgroundAttempt(job: ReplayJob): TechnicalAttempt =
        telemetry.begin(
            TechnicalOperation.REPLAY,
            mode = job.kind.path,
            session = null,
            resourceId = job.resourceId,
            isInternal = job.internal,
        ).also { attempt ->
            telemetry.success(attempt, TechnicalStage.REQUESTED)
            telemetry.success(attempt, TechnicalStage.QUEUED)
        }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    fun scheduledWake() = wake()

    @EventListener(ApplicationReadyEvent::class)
    fun recoverInterruptedJobs() {
        val interrupted = jdbc.sql(
            """
            SELECT * FROM replay_delivery_jobs
             WHERE status IN ('initializing', 'rendering')
             ORDER BY requested_at
            """.trimIndent(),
        ).query(::mapJob).list()
        interrupted.forEach { job ->
            job.attemptId?.let { attempt ->
                telemetry.failure(
                    TechnicalAttempt(attempt, TechnicalOperation.REPLAY, job.kind.path),
                    TechnicalStage.ABANDONED,
                    TechnicalFailure.RESTART,
                )
            }
            val replacement = newBackgroundAttempt(job)
            jdbc.sql(
                """
                UPDATE replay_delivery_jobs
                   SET status = 'queued', attempt_id = :attemptId, next_attempt_at = :now,
                       last_error = 'restart', updated_at = :now
                 WHERE id = :id AND status IN ('initializing', 'rendering')
                """.trimIndent(),
            ).param("attemptId", replacement.id)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", job.id)
                .update()
        }
        refreshPendingGauge()
        wake()
    }

    internal fun wake() {
        refreshPendingGauge()
        if (!workerRunning.compareAndSet(false, true)) return
        try {
            executor.execute {
                activeGauge.set(1)
                try {
                    drainAcknowledgements()
                    repeat(MAX_JOBS_PER_WAKE) {
                        if (!processOne()) return@repeat
                    }
                } finally {
                    activeGauge.set(0)
                    workerRunning.set(false)
                    refreshPendingGauge()
                    if (acknowledgements.isNotEmpty() || hasPendingWork()) wake()
                }
            }
        } catch (error: RuntimeException) {
            workerRunning.set(false)
            logger.warn("Could not schedule replay worker", error)
        }
    }

    private fun drainAcknowledgements() {
        while (true) {
            val acknowledgement = acknowledgements.poll() ?: return
            runCatching {
                telegram.sendMessage(
                    acknowledgement.chatId,
                    GameI18n.t(acknowledgement.language, acknowledgement.messageKey),
                )
            }.onFailure { error ->
                logger.warn("Could not acknowledge replay request for chat {}", acknowledgement.chatId, error)
            }
        }
    }

    internal fun processOne(): Boolean {
        val job = claimNextJob() ?: return false
        val attemptId = job.attemptId ?: return failUninitialized(job)
        val attempt = TechnicalAttempt(attemptId, TechnicalOperation.REPLAY, job.kind.path)
        var coldRender = false
        val artifact = try {
            when (job.kind) {
                ReplayKind.PERSONAL -> replays.preparePersonal(job.playerId, job.resourceId) {
                    coldRender = true
                    telemetry.success(attempt, TechnicalStage.RENDER_STARTED, TechnicalCache.COLD)
                }
                ReplayKind.WEEKLY -> replays.prepareWeekly(job.allianceCode, job.resourceId) {
                    coldRender = true
                    telemetry.success(attempt, TechnicalStage.RENDER_STARTED, TechnicalCache.COLD)
                }
            }
        } catch (error: Exception) {
            val failure = if (error is ReplayRenderException) TechnicalFailure.RENDER_FAILURE else TechnicalFailure.BUSINESS_STALE
            telemetry.failure(attempt, TechnicalStage.READY, failure, if (coldRender) TechnicalCache.COLD else TechnicalCache.NONE)
            markFailed(job, failure.value)
            sendFailureNotice(job, retryable = error is ReplayRenderException)
            logger.warn("Could not prepare {} replay {} for player {}", job.kind.path, job.resourceId, job.playerId, error)
            return true
        }

        val cache = if (artifact.cacheHit) TechnicalCache.HIT else TechnicalCache.COLD
        telemetry.success(attempt, TechnicalStage.READY, cache)
        markReady(job)
        try {
            telegram.sendAnimation(
                job.chatId,
                artifact.url,
                GameI18n.t(job.language, if (job.kind == ReplayKind.PERSONAL) "replay_caption" else "weekly_replay_caption"),
                artifact.width,
                artifact.height,
                artifact.durationSeconds,
                replayNavigationKeyboard(job.language),
            )
            telemetry.success(attempt, TechnicalStage.DELIVERED, cache, terminal = true)
            markDelivered(job)
        } catch (error: Exception) {
            telemetry.failure(attempt, TechnicalStage.DELIVERED, TechnicalFailure.TELEGRAM_SEND, cache)
            handleDeliveryFailure(job, error)
        }
        return true
    }

    private fun failUninitialized(job: ReplayJob): Boolean {
        markFailed(job, TechnicalFailure.PERSISTENCE_FAILURE.value)
        logger.warn("Replay job {} has no technical attempt", job.id)
        return true
    }

    private fun claimNextJob(): ReplayJob? = workerTransaction.execute {
        val job = jdbc.sql(
            """
            SELECT * FROM replay_delivery_jobs
             WHERE status IN ('queued', 'ready') AND next_attempt_at <= :now
             ORDER BY requested_at, id
             LIMIT 1
             FOR UPDATE
            """.trimIndent(),
        ).param("now", Timestamp.from(clock.instant()))
            .query(::mapJob)
            .optional()
            .orElse(null) ?: return@execute null
        if (job.status == ReplayJobStatus.QUEUED) {
            jdbc.sql(
                """
                UPDATE replay_delivery_jobs
                   SET status = 'rendering', render_attempts = render_attempts + 1, updated_at = :now
                 WHERE id = :id
                """.trimIndent(),
            ).param("now", Timestamp.from(clock.instant())).param("id", job.id).update()
        }
        job
    }

    private fun markReady(job: ReplayJob) {
        jdbc.sql(
            """
            UPDATE replay_delivery_jobs
               SET status = 'ready', ready_at = COALESCE(ready_at, :now), last_error = NULL, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        ).param("now", Timestamp.from(clock.instant())).param("id", job.id).update()
    }

    private fun markDelivered(job: ReplayJob) {
        jdbc.sql(
            """
            UPDATE replay_delivery_jobs
               SET status = 'delivered', delivery_attempts = delivery_attempts + 1,
                   delivered_at = :now, last_error = NULL, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        ).param("now", Timestamp.from(clock.instant())).param("id", job.id).update()
    }

    private fun markFailed(job: ReplayJob, error: String) {
        jdbc.sql(
            """
            UPDATE replay_delivery_jobs
               SET status = 'failed', last_error = :error, updated_at = :now
             WHERE id = :id
            """.trimIndent(),
        ).param("error", error.take(64))
            .param("now", Timestamp.from(clock.instant()))
            .param("id", job.id)
            .update()
    }

    private fun handleDeliveryFailure(job: ReplayJob, error: Exception) {
        val attempts = job.deliveryAttempts + 1
        val unavailable = (error as? TelegramDeliveryException)?.playerUnavailable == true
        if (unavailable || attempts >= properties.replay.maxDeliveryAttempts.coerceAtLeast(1)) {
            jdbc.sql(
                """
                UPDATE replay_delivery_jobs
                   SET status = 'failed', delivery_attempts = :attempts,
                       last_error = 'telegram_send', updated_at = :now
                 WHERE id = :id
                """.trimIndent(),
            ).param("attempts", attempts)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", job.id)
                .update()
        } else {
            val retry = newBackgroundAttempt(job)
            val retryAt = clock.instant().plus(
                Duration.ofSeconds(properties.replay.deliveryRetrySeconds.coerceAtLeast(1) * attempts),
            )
            jdbc.sql(
                """
                UPDATE replay_delivery_jobs
                   SET status = 'ready', attempt_id = :attemptId, delivery_attempts = :attempts,
                       last_error = 'telegram_send', next_attempt_at = :retryAt, updated_at = :now
                 WHERE id = :id
                """.trimIndent(),
            ).param("attemptId", retry.id)
                .param("attempts", attempts)
                .param("retryAt", Timestamp.from(retryAt))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", job.id)
                .update()
        }
        logger.warn("Could not deliver {} replay {} to player {}", job.kind.path, job.resourceId, job.playerId, error)
    }

    private fun sendFailureNotice(job: ReplayJob, retryable: Boolean) {
        val key = if (retryable) "replay_failed_retry" else "replay_unavailable"
        val keyboard = if (retryable) InlineKeyboardMarkup(
            listOf(
                listOf(
                    InlineKeyboardButton(
                        GameI18n.t(job.language, "replay_retry_button"),
                        "replay:${if (job.kind == ReplayKind.PERSONAL) "b" else "w"}:${job.resourceId}",
                    ),
                ),
            ),
        ) else null
        runCatching { telegram.sendMessage(job.chatId, GameI18n.t(job.language, key), keyboard) }
            .onFailure { logger.warn("Could not send replay failure notice to player {}", job.playerId, it) }
    }

    private fun replayNavigationKeyboard(language: GameLanguage) = InlineKeyboardMarkup(
        listOf(
            listOf(
                InlineKeyboardButton(GameI18n.t(language, "battle"), "nav:battle"),
                InlineKeyboardButton(GameI18n.t(language, "army"), "nav:army"),
            ),
            listOf(
                InlineKeyboardButton(GameI18n.t(language, "front"), "nav:front"),
                InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"),
            ),
        ),
    )

    private fun findJob(kind: ReplayKind, resourceId: UUID, playerId: Long, lock: Boolean): ReplayJob? = jdbc.sql(
        """
        SELECT * FROM replay_delivery_jobs
         WHERE kind = :kind AND resource_id = :resourceId AND player_telegram_id = :playerId
         ${if (lock) "FOR UPDATE" else ""}
        """.trimIndent(),
    ).param("kind", kind.path)
        .param("resourceId", resourceId)
        .param("playerId", playerId)
        .query(::mapJob)
        .optional()
        .orElse(null)

    private fun hasPendingWork(): Boolean = jdbc.sql(
        "SELECT COUNT(*) FROM replay_delivery_jobs WHERE status IN ('queued', 'ready') AND next_attempt_at <= :now",
    ).param("now", Timestamp.from(clock.instant())).query(Int::class.java).single() > 0

    private fun refreshPendingGauge() {
        runCatching {
            jdbc.sql("SELECT COUNT(*) FROM replay_delivery_jobs WHERE status IN ('queued', 'rendering', 'ready')")
                .query(Int::class.java)
                .single()
        }.onSuccess(pendingGauge::set)
            .onFailure { logger.debug("Could not refresh replay queue gauge", it) }
    }

    private fun mapJob(rs: ResultSet, ignored: Int): ReplayJob = ReplayJob(
        id = rs.getObject("id", UUID::class.java),
        kind = ReplayKind.fromPath(rs.getString("kind")) ?: error("Unknown replay kind"),
        resourceId = rs.getObject("resource_id", UUID::class.java),
        playerId = rs.getLong("player_telegram_id"),
        chatId = rs.getLong("chat_id"),
        language = GameLanguage.fromStored(rs.getString("language")),
        allianceCode = rs.getString("alliance_code"),
        internal = rs.getBoolean("is_internal"),
        status = ReplayJobStatus.fromValue(rs.getString("status")),
        attemptId = rs.getObject("attempt_id", UUID::class.java),
        deliveryAttempts = rs.getInt("delivery_attempts"),
    )

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    companion object {
        private const val MAX_JOBS_PER_WAKE = 4
    }
}

internal enum class ReplayJobStatus(val value: String) {
    INITIALIZING("initializing"),
    QUEUED("queued"),
    RENDERING("rendering"),
    READY("ready"),
    DELIVERED("delivered"),
    FAILED("failed"),
    ;

    companion object {
        fun fromValue(value: String): ReplayJobStatus = entries.first { it.value == value }
    }
}

private data class ReplayJob(
    val id: UUID,
    val kind: ReplayKind,
    val resourceId: UUID,
    val playerId: Long,
    val chatId: Long,
    val language: GameLanguage,
    val allianceCode: String?,
    val internal: Boolean,
    val status: ReplayJobStatus,
    val attemptId: UUID?,
    val deliveryAttempts: Int,
)

private data class ReplayAcknowledgement(
    val chatId: Long,
    val language: GameLanguage,
    val messageKey: String,
)
