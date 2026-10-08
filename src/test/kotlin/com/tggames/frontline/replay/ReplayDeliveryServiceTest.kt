package com.tggames.frontline.replay

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.observability.PlayerJourney
import com.tggames.frontline.observability.TechnicalOperationTelemetry
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.core.task.TaskExecutor
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReplayDeliveryServiceTest {
    @Test
    fun `two concurrent requests share one durable job and attempt`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val calls = (1..2).map {
            pool.submit {
                start.await()
                env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
            }
        }
        start.countDown()
        calls.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertThat(env.count("replay_delivery_jobs")).isEqualTo(1)
        assertThat(env.count("technical_operation_attempts")).isEqualTo(1)
        assertThat(env.status()).isEqualTo("queued")
    }

    @Test
    fun `cache hit becomes ready and is delivered`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        val artifact = artifact("https://example.test/current.mp4", cacheHit = true)
        doReturn(artifact).`when`(env.replays).preparePersonal(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue(resourceId),
            anyCallback(),
        )

        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        assertThat(env.service.processOne()).isTrue()

        assertThat(env.status()).isEqualTo("delivered")
        assertThat(env.stageCache("ready")).isEqualTo("hit")
        verify(env.telegram).sendAnimation(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue(artifact.url),
            anyString(),
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
        )
    }

    @Test
    fun `render failure keeps a safe explicit retry`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        doAnswer { throw ReplayRenderException(IllegalStateException("ffmpeg")) }
            .`when`(env.replays)
            .preparePersonal(org.mockito.Mockito.eq(PLAYER_ID), eqValue(resourceId), anyCallback())

        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.RU, resourceId)
        env.service.processOne()

        assertThat(env.status()).isEqualTo("failed")
        assertThat(env.lastError()).isEqualTo("render_failure")
        verify(env.telegram).sendMessage(
            org.mockito.Mockito.eq(PLAYER_ID),
            containsValue("попробовать"),
            any(),
        )
    }

    @Test
    fun `Telegram failure preserves ready replay and user retry delivers it`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        val artifact = artifact("https://example.test/retry.mp4", cacheHit = true)
        doReturn(artifact).`when`(env.replays).preparePersonal(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue(resourceId),
            anyCallback(),
        )
        val sends = AtomicInteger()
        doAnswer {
            if (sends.getAndIncrement() == 0) {
                throw TelegramDeliveryException(false, "sendAnimation", IllegalStateException("network"))
            }
            Unit
        }.`when`(env.telegram).sendAnimation(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any())

        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        env.service.processOne()
        assertThat(env.status()).isEqualTo("ready")

        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        env.service.processOne()

        assertThat(env.status()).isEqualTo("delivered")
        verify(env.telegram, times(2)).sendAnimation(anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any())
    }

    @Test
    fun `restart returns an interrupted render to the queue`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        val originalAttempt = env.attemptId()
        env.jdbc.sql("UPDATE replay_delivery_jobs SET status = 'rendering'").update()

        env.service.recoverInterruptedJobs()

        assertThat(env.status()).isEqualTo("queued")
        assertThat(env.lastError()).isEqualTo("restart")
        assertThat(env.attemptId()).isNotEqualTo(originalAttempt)
    }

    @Test
    fun `repeat delivery prepares the artifact again so an expired cache file is rebuilt`() {
        val env = environment()
        val resourceId = UUID.randomUUID()
        doReturn(
            artifact("https://example.test/first.mp4", cacheHit = false),
            artifact("https://example.test/refreshed.mp4", cacheHit = false),
        ).`when`(env.replays).preparePersonal(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue(resourceId),
            anyCallback(),
        )

        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        env.service.processOne()
        env.service.requestPersonal(PLAYER_ID, PLAYER_ID, GameLanguage.EN, resourceId)
        env.service.processOne()

        verify(env.replays, times(2)).preparePersonal(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue(resourceId),
            anyCallback(),
        )
        verify(env.telegram).sendAnimation(
            org.mockito.Mockito.eq(PLAYER_ID),
            eqValue("https://example.test/refreshed.mp4"),
            anyString(),
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
        )
    }

    private fun environment(): TestEnvironment {
        val name = UUID.randomUUID().toString().replace("-", "")
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:replay_delivery_$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        val jdbc = JdbcClient.create(dataSource)
        createSchema(jdbc)
        jdbc.sql("INSERT INTO players(telegram_id) VALUES (:id)").param("id", PLAYER_ID).update()
        val clock = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC)
        val registry = SimpleMeterRegistry()
        val transactionManager = DataSourceTransactionManager(dataSource)
        val telemetry = TechnicalOperationTelemetry(jdbc, registry, transactionManager, clock)
        val replays = mock(ReplayService::class.java)
        val telegram = mock(TelegramClient::class.java)
        val journey = mock(PlayerJourney::class.java)
        val executor = TaskExecutor { }
        val properties = FrontlineProperties(
            replay = FrontlineProperties.Replay(maxDeliveryAttempts = 3, deliveryRetrySeconds = 1),
        )
        val service = ReplayDeliveryService(
            jdbc,
            replays,
            telegram,
            telemetry,
            journey,
            properties,
            executor,
            transactionManager,
            registry,
            clock,
        )
        return TestEnvironment(jdbc, service, replays, telegram)
    }

    private fun createSchema(jdbc: JdbcClient) {
        jdbc.sql("CREATE TABLE players(telegram_id BIGINT PRIMARY KEY)").update()
        jdbc.sql(
            """
            CREATE TABLE technical_operation_attempts(
                attempt_id UUID PRIMARY KEY, operation VARCHAR(16), mode VARCHAR(16), session_id UUID,
                resource_id UUID, engine_version SMALLINT, experiment_variant VARCHAR(32), is_internal BOOLEAN,
                started_at TIMESTAMP WITH TIME ZONE, last_stage VARCHAR(32), last_stage_at TIMESTAMP WITH TIME ZONE,
                completed_at TIMESTAMP WITH TIME ZONE, outcome VARCHAR(16), failure_class VARCHAR(32), cache_status VARCHAR(16)
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE technical_operation_stages(
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                attempt_id UUID REFERENCES technical_operation_attempts(attempt_id), stage VARCHAR(32),
                occurred_at TIMESTAMP WITH TIME ZONE, duration_ms BIGINT, result VARCHAR(16),
                failure_class VARCHAR(32), cache_status VARCHAR(16), UNIQUE(attempt_id, stage)
            )
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            CREATE TABLE replay_delivery_jobs(
                id UUID PRIMARY KEY, kind VARCHAR(16), resource_id UUID, player_telegram_id BIGINT REFERENCES players(telegram_id),
                chat_id BIGINT, language VARCHAR(8), alliance_code VARCHAR(8), is_internal BOOLEAN DEFAULT FALSE,
                status VARCHAR(16), attempt_id UUID, render_attempts INTEGER DEFAULT 0, delivery_attempts INTEGER DEFAULT 0,
                last_error VARCHAR(64), next_attempt_at TIMESTAMP WITH TIME ZONE, requested_at TIMESTAMP WITH TIME ZONE,
                updated_at TIMESTAMP WITH TIME ZONE, ready_at TIMESTAMP WITH TIME ZONE, delivered_at TIMESTAMP WITH TIME ZONE,
                UNIQUE(kind, resource_id, player_telegram_id)
            )
            """.trimIndent(),
        ).update()
    }

    private fun artifact(url: String, cacheHit: Boolean) = ReplayArtifact(url, 768, 768, 20, cacheHit)

    private fun <T : Any> eqValue(value: T): T = org.mockito.Mockito.eq(value) ?: value

    private fun anyCallback(): () -> Unit = any<() -> Unit>() ?: {}

    private fun containsValue(value: String): String = org.mockito.Mockito.contains(value) ?: value

    private data class TestEnvironment(
        val jdbc: JdbcClient,
        val service: ReplayDeliveryService,
        val replays: ReplayService,
        val telegram: TelegramClient,
    ) {
        fun count(table: String): Int = jdbc.sql("SELECT COUNT(*) FROM $table").query(Int::class.java).single()
        fun status(): String = jdbc.sql("SELECT status FROM replay_delivery_jobs").query(String::class.java).single()
        fun lastError(): String? = jdbc.sql("SELECT last_error FROM replay_delivery_jobs").query(String::class.java).optional().orElse(null)
        fun attemptId(): UUID = jdbc.sql("SELECT attempt_id FROM replay_delivery_jobs").query(UUID::class.java).single()
        fun stageCache(stage: String): String = jdbc.sql(
            "SELECT cache_status FROM technical_operation_stages WHERE stage = :stage",
        ).param("stage", stage).query(String::class.java).single()
    }

    companion object {
        private const val PLAYER_ID = 42L
    }
}
