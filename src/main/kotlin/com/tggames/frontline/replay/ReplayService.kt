package com.tggames.frontline.replay

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.campaign.WeeklyBattleEvent
import com.tggames.frontline.campaign.WeeklyFormationResult
import com.tggames.frontline.config.FrontlineProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class ReplayService(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val properties: FrontlineProperties,
    private val renderer: BattleReplayRenderer,
    private val encoder: ReplayVideoEncoder,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, Any>()

    fun preparePersonal(playerId: Long, battleId: UUID): ReplayArtifact {
        val snapshot = jdbc.sql(
            """
            SELECT player_telegram_id, map_snapshot_json, group_snapshot_json,
                   enemy_group_snapshot_json, spatial_events_json, deployment_entry, enemy_entry
              FROM battles
             WHERE id = :id AND map_snapshot_json IS NOT NULL AND spatial_events_json IS NOT NULL
            """.trimIndent(),
        ).param("id", battleId)
            .query { rs, _ ->
                check(rs.getLong("player_telegram_id") == playerId) { "Battle does not belong to player" }
                PersonalReplaySnapshot(
                    battleId = battleId,
                    map = objectMapper.readValue(rs.getString("map_snapshot_json"), BattleMapDefinition::class.java),
                    playerGroup = objectMapper.readValue(rs.getString("group_snapshot_json"), CombatGroupSnapshot::class.java),
                    enemyGroup = objectMapper.readValue(rs.getString("enemy_group_snapshot_json"), CombatGroupSnapshot::class.java),
                    playerEntryId = rs.getString("deployment_entry"),
                    enemyEntryId = rs.getString("enemy_entry"),
                    events = objectMapper.readValue(rs.getString("spatial_events_json"), object : TypeReference<List<SpatialBattleEvent>>() {}),
                )
            }.optional().orElseThrow { IllegalArgumentException("Battle replay not found") }
        return prepare(ReplayKind.PERSONAL, battleId, snapshot.events.maxOfOrNull { it.step } ?: 1) {
            renderer.personal(snapshot)
        }
    }

    fun prepareWeekly(allianceCode: String?, matchupId: UUID): ReplayArtifact {
        require(!allianceCode.isNullOrBlank()) { "Player has no alliance" }
        val snapshot = jdbc.sql(
            """
            SELECT map_snapshot_json, formations_json, events_json, max_ticks, alliance_a, alliance_b
              FROM campaign_matchups
             WHERE id = :id AND resolved_at IS NOT NULL
            """.trimIndent(),
        ).param("id", matchupId)
            .query { rs, _ ->
                check(allianceCode == rs.getString("alliance_a") || allianceCode == rs.getString("alliance_b")) {
                    "Matchup does not belong to alliance"
                }
                WeeklyReplaySnapshot(
                    matchupId = matchupId,
                    map = objectMapper.readValue(rs.getString("map_snapshot_json"), BattleMapDefinition::class.java),
                    formations = objectMapper.readValue(rs.getString("formations_json"), object : TypeReference<List<WeeklyFormationResult>>() {}),
                    events = objectMapper.readValue(rs.getString("events_json"), object : TypeReference<List<WeeklyBattleEvent>>() {}),
                    maxTicks = rs.getInt("max_ticks"),
                )
            }.optional().orElseThrow { IllegalArgumentException("Weekly replay not found") }
        require(snapshot.formations.all { it.id.isNotBlank() && it.initialPosition != null }) { "This battle predates visual replay events" }
        return prepare(ReplayKind.WEEKLY, matchupId, snapshot.events.maxOfOrNull { it.tick } ?: snapshot.maxTicks) {
            renderer.weekly(snapshot)
        }
    }

    fun open(kind: ReplayKind, id: UUID, token: String): ReplayFile? {
        if (!MessageDigest.isEqual(token.toByteArray(StandardCharsets.US_ASCII), token(kind, id).toByteArray(StandardCharsets.US_ASCII))) return null
        val path = cachePath(kind, id)
        if (!Files.isRegularFile(path)) return null
        return ReplayFile(path, Files.size(path))
    }

    internal fun token(kind: ReplayKind, id: UUID): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.battleServerSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("replay:${kind.path}:$id:$PRESENTATION_VERSION".toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun prepare(kind: ReplayKind, id: UUID, steps: Int, frames: () -> Sequence<java.awt.image.BufferedImage>): ReplayArtifact {
        cleanupExpired()
        val path = cachePath(kind, id)
        synchronized(locks.computeIfAbsent("${kind.path}:$id") { Any() }) {
            if (!Files.isRegularFile(path)) {
                logger.info("Rendering {} battle replay {}", kind.path, id)
                encoder.encode(frames(), path)
                if (Files.size(path) > MAX_TELEGRAM_URL_BYTES) {
                    Files.deleteIfExists(path)
                    error("Replay exceeds Telegram URL upload limit")
                }
            }
        }
        val duration = (properties.replay.fps * 3 + maxOf(1, steps) * 3 + properties.replay.fps - 1) / properties.replay.fps
        val url = properties.publicBaseUrl.trimEnd('/') + "/api/v1/replays/${kind.path}/$id.mp4?token=${token(kind, id)}"
        return ReplayArtifact(url, properties.replay.width, properties.replay.width, duration)
    }

    private fun cachePath(kind: ReplayKind, id: UUID): Path =
        Path.of(properties.replay.cacheDirectory).resolve("${kind.path}-$id-$PRESENTATION_VERSION.mp4")

    internal fun cleanupExpired(now: Instant = Instant.now()) {
        val directory = Path.of(properties.replay.cacheDirectory)
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files ->
            files.filter { path ->
                if (!Files.isRegularFile(path)) return@filter false
                val retentionHours = when {
                    path.fileName.toString().startsWith("${ReplayKind.WEEKLY.path}-") ->
                        properties.replay.weeklyRetentionHours.coerceAtLeast(MIN_WEEKLY_RETENTION_HOURS)
                    else -> properties.replay.retentionHours.coerceAtLeast(1)
                }
                Files.getLastModifiedTime(path).toInstant().isBefore(now.minus(Duration.ofHours(retentionHours)))
            }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    companion object {
        private const val MAX_TELEGRAM_URL_BYTES = 19_000_000L
        internal const val MIN_WEEKLY_RETENTION_HOURS = 168L
        internal const val PRESENTATION_VERSION = "v2"
    }
}
