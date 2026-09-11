package com.tggames.frontline.replay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.config.FrontlineProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ReplayServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `cached replay requires the battle-specific signature`() {
        val properties = FrontlineProperties(
            battleServerSalt = "replay-test-secret",
            replay = FrontlineProperties.Replay(cacheDirectory = directory.toString()),
        )
        val service = ReplayService(
            mock(JdbcClient::class.java),
            jacksonObjectMapper(),
            properties,
            mock(BattleReplayRenderer::class.java),
            mock(ReplayVideoEncoder::class.java),
        )
        val id = UUID.randomUUID()
        val path = directory.resolve("personal-$id-${ReplayService.PRESENTATION_VERSION}.mp4")
        Files.write(path, byteArrayOf(1, 2, 3, 4))

        assertThat(service.open(ReplayKind.PERSONAL, id, "invalid")).isNull()
        assertThat(service.open(ReplayKind.WEEKLY, id, service.token(ReplayKind.PERSONAL, id))).isNull()
        assertThat(service.open(ReplayKind.PERSONAL, id, service.token(ReplayKind.PERSONAL, id))?.contentLength).isEqualTo(4)
    }

    @Test
    fun `weekly replay cache survives for at least seven days`() {
        val properties = FrontlineProperties(
            replay = FrontlineProperties.Replay(
                cacheDirectory = directory.toString(),
                retentionHours = 48,
                weeklyRetentionHours = 24,
            ),
        )
        val service = ReplayService(
            mock(JdbcClient::class.java),
            jacksonObjectMapper(),
            properties,
            mock(BattleReplayRenderer::class.java),
            mock(ReplayVideoEncoder::class.java),
        )
        val now = Instant.parse("2026-09-11T12:00:00Z")
        val personal = cachedFile("personal-old-v2.mp4", now.minus(Duration.ofHours(72)))
        val weeklyCurrent = cachedFile("weekly-current-v2.mp4", now.minus(Duration.ofHours(120)))
        val weeklyExpired = cachedFile("weekly-expired-v2.mp4", now.minus(Duration.ofHours(169)))

        service.cleanupExpired(now)

        assertThat(personal).doesNotExist()
        assertThat(weeklyCurrent).exists()
        assertThat(weeklyExpired).doesNotExist()
    }

    private fun cachedFile(name: String, modifiedAt: Instant): Path = directory.resolve(name).also {
        Files.write(it, byteArrayOf(1))
        Files.setLastModifiedTime(it, FileTime.from(modifiedAt))
    }
}
