package com.tggames.frontline.replay

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tggames.frontline.battle.BattleMapCatalog
import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.battle.UnitBattleSnapshot
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.config.FrontlineProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.DataBufferByte
import java.nio.charset.StandardCharsets
import java.util.UUID

class BattleReplayRendererTest {
    private val mapper = jacksonObjectMapper()
    private val equipment = EquipmentCatalog(mapper)
    private val map = BattleMapCatalog(mapper).maps.first()
    private val properties = FrontlineProperties(replay = FrontlineProperties.Replay(width = 512, fps = 3))
    private val renderer = BattleReplayRenderer(properties)

    @Test
    fun `personal replay animates movement fire damage and capture on the stored map`() {
        val player = unit("MBT", "player")
        val enemy = unit("LIGHT_ARMOR", "enemy")
        val from = map.playerEntries.first().position
        val moved = map.neighbors(from).first()
        val enemyPosition = map.enemyEntries.first().position
        val objective = map.objectives.first()
        val snapshot = PersonalReplaySnapshot(
            battleId = stableUuid("battle"),
            map = map,
            playerGroup = CombatGroupSnapshot(stableUuid("player-group"), 1, 10, listOf(player)),
            enemyGroup = CombatGroupSnapshot(stableUuid("enemy-group"), 1, 10, listOf(enemy)),
            playerEntryId = map.playerEntries.first().id,
            enemyEntryId = map.enemyEntries.first().id,
            events = listOf(
                SpatialBattleEvent(1, SpatialEventType.UNIT_MOVED, BattleSide.PLAYER, player.id, player.code, from, moved),
                SpatialBattleEvent(1, SpatialEventType.UNIT_HIT, BattleSide.PLAYER, player.id, player.code, moved, enemyPosition, enemy.id, enemy.code, amount = 55),
                SpatialBattleEvent(2, SpatialEventType.CAPTURE_PROGRESS, BattleSide.PLAYER, objectiveId = objective.id, amount = 1),
                SpatialBattleEvent(2, SpatialEventType.OBJECTIVE_CAPTURED, BattleSide.PLAYER, objectiveId = objective.id),
            ),
        )

        val frames = renderer.personal(snapshot).toList()

        assertThat(frames).hasSize(properties.replay.fps + 2 * 3 + properties.replay.fps * 2)
        assertThat(frames).allSatisfy {
            assertThat(it.width).isEqualTo(512)
            assertThat(it.height).isEqualTo(512)
            assertThat(it.type).isEqualTo(java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
        }
        assertThat(frames.map(::checksum).distinct()).hasSizeGreaterThan(3)
    }

    private fun unit(code: String, key: String): UnitBattleSnapshot {
        val definition = equipment.require(code)
        return UnitBattleSnapshot(
            id = stableUuid(key),
            code = code,
            level = 1,
            cpCost = definition.cpCost,
            attack = definition.stats.attack,
            armor = definition.stats.armor,
            mobility = definition.stats.mobility,
            recon = definition.stats.recon,
            support = definition.stats.support,
            roles = definition.roles,
            movementProfile = definition.spatial.movementProfile,
            movementPoints = definition.spatial.movementPoints,
            weaponRange = definition.spatial.weaponRange,
            minimumRange = definition.spatial.minimumRange,
            sightRange = definition.spatial.sightRange,
            fireMode = definition.spatial.fireMode,
        )
    }

    private fun checksum(image: java.awt.image.BufferedImage): Long =
        (image.raster.dataBuffer as DataBufferByte).data.fold(1L) { hash, byte -> hash * 31 + byte }

    private fun stableUuid(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))
}
