package com.tggames.frontline.replay

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.HexCoord
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.campaign.WeeklyEventType
import com.tggames.frontline.campaign.WeeklySide
import com.tggames.frontline.config.FrontlineProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

@Component
class BattleReplayRenderer(private val properties: FrontlineProperties) {
    private val iconCache = ConcurrentHashMap<String, BufferedImage>()

    fun personal(snapshot: PersonalReplaySnapshot): Sequence<BufferedImage> = sequence {
        val entry = snapshot.map.playerEntries.first { it.id == snapshot.playerEntryId }.position
        val enemyEntry = snapshot.map.enemyEntries.first { it.id == snapshot.enemyEntryId }.position
        val units = linkedMapOf<String, ReplayUnit>()
        snapshot.playerGroup.units.forEach {
            units[it.id.toString()] = ReplayUnit(it.id.toString(), it.code, ReplayTeam.BLUE, entry, it.quantity, 100L * it.quantity, 100L * it.quantity)
        }
        snapshot.enemyGroup.units.forEach {
            units[it.id.toString()] = ReplayUnit(it.id.toString(), it.code, ReplayTeam.RED, enemyEntry, it.quantity, 100L * it.quantity, 100L * it.quantity)
        }
        val objectives = snapshot.map.objectives.associate { it.id to ReplayObjective() }.toMutableMap()
        val base = loadMap(snapshot.map, ReplayKind.PERSONAL)
        val maxStep = max(1, snapshot.events.maxOfOrNull { it.step } ?: 1)
        repeat(properties.replay.fps) { yield(draw(base, snapshot.map, units.values, objectives, 0, maxStep)) }

        val eventsByStep = snapshot.events.groupBy { it.step }
        (1..maxStep).forEach { step ->
            val events = eventsByStep[step].orEmpty()
            val movements = events.filter { it.type == SpatialEventType.UNIT_MOVED }
            yield(draw(base, snapshot.map, units.values, objectives, step, maxStep, movements.associate { it.unitId.toString() to Interpolation(requireNotNull(it.from), requireNotNull(it.to), .5) }))
            movements.forEach { event -> units[event.unitId.toString()]?.position = requireNotNull(event.to) }

            val shots = mutableListOf<ReplayShot>()
            val destroyed = mutableSetOf<String>()
            events.forEach { event ->
                when (event.type) {
                    SpatialEventType.SHOT_MISSED -> {
                        val target = units[event.targetUnitId.toString()]
                        if (target != null && event.from != null) shots += ReplayShot(event.from, target.position, false, event.side == BattleSide.PLAYER)
                    }
                    SpatialEventType.UNIT_HIT -> {
                        val target = units[event.targetUnitId.toString()]
                        if (target != null && event.from != null) {
                            shots += ReplayShot(event.from, event.to ?: target.position, true, event.side == BattleSide.PLAYER)
                            target.power = (target.power - (event.amount ?: 0)).coerceAtLeast(0)
                        }
                    }
                    SpatialEventType.UNIT_DESTROYED -> destroyed += event.targetUnitId.toString()
                    SpatialEventType.CAPTURE_PROGRESS -> objectives[event.objectiveId]?.apply {
                        progress = event.amount ?: 0
                        progressTeam = if (event.side == BattleSide.PLAYER) ReplayTeam.BLUE else ReplayTeam.RED
                    }
                    SpatialEventType.OBJECTIVE_CAPTURED -> objectives[event.objectiveId]?.apply {
                        owner = if (event.side == BattleSide.PLAYER) ReplayTeam.BLUE else ReplayTeam.RED
                        progress = 0
                        progressTeam = null
                    }
                    SpatialEventType.UNIT_ROUTED -> units[event.unitId.toString()]?.routed = true
                    SpatialEventType.UNIT_MOVED -> Unit
                }
            }
            yield(draw(base, snapshot.map, units.values, objectives, step, maxStep, shots = shots, destroyed = destroyed))
            destroyed.forEach(units::remove)
            yield(draw(base, snapshot.map, units.values, objectives, step, maxStep))
        }
        repeat(properties.replay.fps * 2) { yield(draw(base, snapshot.map, units.values, objectives, maxStep, maxStep, final = true)) }
    }

    fun weekly(snapshot: WeeklyReplaySnapshot): Sequence<BufferedImage> = sequence {
        val units = snapshot.formations.associate { formation ->
            formation.id to ReplayUnit(
                formation.id,
                formation.unitCode,
                if (formation.side == WeeklySide.A) ReplayTeam.BLUE else ReplayTeam.RED,
                formation.initialPosition ?: formation.position,
                formation.quantity,
                formation.initialPower,
                formation.initialPower,
            )
        }.toMutableMap()
        val objectives = snapshot.map.objectives.associate { it.id to ReplayObjective() }.toMutableMap()
        val base = loadMap(snapshot.map, ReplayKind.WEEKLY)
        val maxTick = max(1, snapshot.events.maxOfOrNull { it.tick } ?: snapshot.maxTicks)
        repeat(properties.replay.fps) { yield(draw(base, snapshot.map, units.values, objectives, 0, maxTick)) }

        val eventsByTick = snapshot.events.groupBy { it.tick }
        (1..maxTick).forEach { tick ->
            val events = eventsByTick[tick].orEmpty()
            val movements = events.filter { it.type == WeeklyEventType.FORMATION_MOVED && it.formationId != null && it.from != null && it.to != null }
            yield(draw(base, snapshot.map, units.values, objectives, tick, maxTick, movements.associate { requireNotNull(it.formationId) to Interpolation(requireNotNull(it.from), requireNotNull(it.to), .5) }))
            movements.forEach { units[it.formationId]?.position = requireNotNull(it.to) }

            val shots = mutableListOf<ReplayShot>()
            val destroyed = mutableSetOf<String>()
            events.forEach { event ->
                when (event.type) {
                    WeeklyEventType.FORMATION_HIT -> {
                        val target = units[event.targetFormationId]
                        if (target != null && event.from != null) {
                            shots += ReplayShot(event.from, event.to ?: target.position, true, event.side == WeeklySide.A)
                            target.power = (target.power - event.amount).coerceAtLeast(0)
                        }
                    }
                    WeeklyEventType.FORMATION_DESTROYED -> event.targetFormationId?.let(destroyed::add)
                    WeeklyEventType.OBJECTIVE_PROGRESS -> objectives[event.objectiveId]?.apply {
                        progress = event.amount.toInt()
                        progressTeam = if (event.side == WeeklySide.A) ReplayTeam.BLUE else ReplayTeam.RED
                    }
                    WeeklyEventType.OBJECTIVE_LOST -> objectives[event.objectiveId]?.owner = null
                    WeeklyEventType.OBJECTIVE_CAPTURED -> objectives[event.objectiveId]?.apply {
                        owner = if (event.side == WeeklySide.A) ReplayTeam.BLUE else ReplayTeam.RED
                        progress = 0
                        progressTeam = null
                    }
                    WeeklyEventType.FORMATION_MOVED, null -> Unit
                }
            }
            yield(draw(base, snapshot.map, units.values, objectives, tick, maxTick, shots = shots, destroyed = destroyed))
            destroyed.forEach(units::remove)
            yield(draw(base, snapshot.map, units.values, objectives, tick, maxTick))
        }
        repeat(properties.replay.fps * 2) { yield(draw(base, snapshot.map, units.values, objectives, maxTick, maxTick, final = true)) }
    }

    private fun draw(
        base: BufferedImage,
        map: BattleMapDefinition,
        units: Collection<ReplayUnit>,
        objectives: Map<String, ReplayObjective>,
        step: Int,
        maxStep: Int,
        interpolations: Map<String, Interpolation> = emptyMap(),
        shots: List<ReplayShot> = emptyList(),
        destroyed: Set<String> = emptySet(),
        final: Boolean = false,
    ): BufferedImage {
        val size = properties.replay.width
        val image = BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val margin = viewportMargin(size)
        graphics.color = Color(7, 18, 25)
        graphics.fillRect(0, 0, size, size)
        graphics.drawImage(base, margin, margin, size - margin, size - margin, 0, 0, base.width, base.height, null)
        drawHud(graphics, size, step, maxStep, final)
        map.objectives.forEachIndexed { index, objective ->
            drawObjective(graphics, map, objective.position, (index + 1).toString(), objectives.getValue(objective.id))
        }
        shots.forEach { drawShot(graphics, map, it) }
        val projected = units.sortedBy { it.id }.associateWith { unit ->
            interpolations[unit.id]?.let { interpolation ->
                val from = cellCenter(map, interpolation.from)
                val to = cellCenter(map, interpolation.to)
                Point2D.Double(
                    from.x + (to.x - from.x) * interpolation.fraction,
                    from.y + (to.y - from.y) * interpolation.fraction,
                )
            } ?: cellCenter(map, unit.position)
        }
        projected.entries.groupBy { (it.value.x / 4).roundToInt() to (it.value.y / 4).roundToInt() }.values.forEach { stack ->
            stack.forEachIndexed { index, (unit, point) ->
                drawUnit(graphics, map, unit, stackedPoint(point, index, stack.size, map.width > 10), unit.id in destroyed)
            }
        }
        graphics.dispose()
        return image
    }

    private fun drawObjective(graphics: Graphics2D, map: BattleMapDefinition, position: HexCoord, label: String, state: ReplayObjective) {
        val center = cellCenter(map, position)
        val radius = if (map.width > 10) 16.0 else 24.0
        graphics.color = when (state.owner) {
            ReplayTeam.BLUE -> BLUE
            ReplayTeam.RED -> RED
            null -> GOLD
        }
        graphics.stroke = BasicStroke(if (state.owner == null) 3f else 5f)
        graphics.draw(Ellipse2D.Double(center.x - radius, center.y - radius, radius * 2, radius * 2))
        if (state.progress > 0) {
            graphics.color = if (state.progressTeam == ReplayTeam.BLUE) BLUE_LIGHT else RED_LIGHT
            graphics.stroke = BasicStroke(5f)
            graphics.draw(Arc2D.Double(center.x - radius - 4, center.y - radius - 4, (radius + 4) * 2, (radius + 4) * 2, 90.0, -120.0 * state.progress, Arc2D.OPEN))
        }
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, if (map.width > 10) 12 else 16)
        graphics.color = Color.WHITE
        val text = label.takeLast(2).uppercase()
        graphics.drawString(text, (center.x - graphics.fontMetrics.stringWidth(text) / 2).toFloat(), (center.y + 5).toFloat())
    }

    private fun drawUnit(graphics: Graphics2D, map: BattleMapDefinition, unit: ReplayUnit, point: Point2D.Double, destroyed: Boolean) {
        val marker = if (map.width > 10) 28 else 42
        val x = (point.x - marker / 2).roundToInt()
        val y = (point.y - marker / 2).roundToInt()
        val teamColor = if (unit.team == ReplayTeam.BLUE) BLUE else RED
        graphics.color = Color(5, 12, 18, 220)
        graphics.fillOval(x - 4, y - 4, marker + 8, marker + 8)
        graphics.color = teamColor
        graphics.stroke = BasicStroke(4f)
        graphics.drawOval(x - 3, y - 3, marker + 6, marker + 6)
        val oldComposite = graphics.composite
        if (unit.routed) graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .45f)
        graphics.drawImage(icon(unit.code), x, y, marker, marker, null)
        graphics.composite = oldComposite
        val barWidth = marker + 4
        val ratio = if (unit.initialPower == 0L) 0.0 else unit.power.toDouble() / unit.initialPower
        graphics.color = Color(10, 12, 14, 220)
        graphics.fillRect(x - 2, y + marker + 3, barWidth, 5)
        graphics.color = when {
            ratio > .6 -> Color(83, 211, 121)
            ratio > .3 -> GOLD
            else -> RED_LIGHT
        }
        graphics.fillRect(x - 2, y + marker + 3, (barWidth * ratio.coerceIn(0.0, 1.0)).roundToInt(), 5)
        if (unit.quantity > 1) {
            val badge = if (map.width > 10) 17 else 21
            graphics.color = Color(4, 9, 13, 235)
            graphics.fillOval(x + marker - badge + 4, y - 5, badge, badge)
            graphics.color = Color.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, if (map.width > 10) 9 else 11)
            val quantity = if (unit.quantity > 999) "999+" else unit.quantity.toString()
            graphics.drawString(quantity, x + marker - badge + 5 + (badge - graphics.fontMetrics.stringWidth(quantity)) / 2, y + 8)
        }
        if (destroyed) {
            graphics.color = Color(255, 235, 220)
            graphics.stroke = BasicStroke(6f)
            graphics.drawLine(x - 2, y - 2, x + marker + 2, y + marker + 2)
            graphics.drawLine(x + marker + 2, y - 2, x - 2, y + marker + 2)
        }
    }

    private fun drawShot(graphics: Graphics2D, map: BattleMapDefinition, shot: ReplayShot) {
        val from = cellCenter(map, shot.from)
        val to = cellCenter(map, shot.to)
        graphics.color = if (!shot.hit) Color(220, 225, 230, 190) else if (shot.blue) BLUE_LIGHT else RED_LIGHT
        graphics.stroke = if (shot.hit) BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) else BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(8f, 7f), 0f)
        graphics.drawLine(from.x.roundToInt(), from.y.roundToInt(), to.x.roundToInt(), to.y.roundToInt())
        if (shot.hit) {
            graphics.color = Color(255, 203, 72, 220)
            graphics.fillOval(to.x.roundToInt() - 9, to.y.roundToInt() - 9, 18, 18)
        }
    }

    private fun drawHud(graphics: Graphics2D, size: Int, step: Int, maxStep: Int, final: Boolean) {
        graphics.color = Color(4, 10, 15, 220)
        graphics.fillRoundRect(20, 18, size - 40, 58, 18, 18)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        graphics.color = Color.WHITE
        graphics.drawString("FRONTLINE NATIONS", 42, 54)
        val status = if (final) "✓" else "$step / $maxStep"
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        graphics.drawString(status, size - 42 - graphics.fontMetrics.stringWidth(status), 53)
        graphics.color = BLUE
        graphics.fillOval(42, size - 48, 18, 18)
        graphics.color = Color.WHITE
        graphics.drawString("A", 68, size - 32)
        graphics.color = RED
        graphics.fillOval(size - 150, size - 48, 18, 18)
        graphics.color = Color.WHITE
        graphics.drawString("B", size - 124, size - 32)
    }

    private fun stackedPoint(center: Point2D.Double, index: Int, count: Int, compact: Boolean): Point2D.Double {
        if (count == 1) return center
        val spread = if (compact) 9.0 else 15.0
        val offsets = listOf(
            0.0 to 0.0,
            -spread to -spread * .65,
            spread to -spread * .65,
            -spread to spread * .65,
            spread to spread * .65,
            0.0 to -spread,
            0.0 to spread,
        )
        val offset = offsets[index % offsets.size]
        return Point2D.Double(center.x + offset.first, center.y + offset.second)
    }

    private fun loadMap(map: BattleMapDefinition, kind: ReplayKind): BufferedImage {
        val path = "static/assets/maps/${kind.path}/${map.id}.png"
        return ClassPathResource(path).inputStream.use(ImageIO::read)
            ?: error("Could not load replay map $path")
    }

    private fun icon(code: String): BufferedImage = iconCache.getOrPut(code) {
        val name = when (code) {
            "MBT" -> "mbt"
            "LIGHT_ARMOR" -> "light-armor"
            "ARTILLERY" -> "artillery"
            "ATTACK_AIRCRAFT" -> "attack-aircraft"
            "FIGHTER" -> "fighter"
            "AIR_DEFENSE" -> "air-defense"
            "RECON_VEHICLE" -> "recon-vehicle"
            else -> "recon-vehicle"
        }
        ClassPathResource("static/assets/units/$name.png").inputStream.use(ImageIO::read)
            ?: error("Could not load unit icon $name")
    }

    private fun cellCenter(map: BattleMapDefinition, position: HexCoord): Point2D.Double {
        val geometry = tileGeometry(map.width, map.height)
        val originX = (SOURCE_SIZE - geometry.boardWidth) / 2.0
        val originY = (SOURCE_SIZE - geometry.boardHeight) / 2.0
        val x = originX + position.q * geometry.xStep + (if (position.r and 1 == 1) geometry.xStep / 2.0 else 0.0) + geometry.tileWidth / 2.0
        val y = originY + position.r * geometry.yStep + geometry.tileHeight / 2.0
        val margin = viewportMargin(properties.replay.width)
        val scale = (properties.replay.width - margin * 2).toDouble() / SOURCE_SIZE
        return Point2D.Double(margin + x * scale, margin + y * scale)
    }

    private fun viewportMargin(size: Int): Int = (size * .068).roundToInt()

    private fun tileGeometry(width: Int, height: Int): TileGeometry {
        for (tileWidth in 150 downTo 48) {
            val tileHeight = (tileWidth * 110.0 / 96.0).roundToInt()
            val xStep = tileWidth - max(1, tileWidth / 96)
            val yStep = (tileHeight * .75).roundToInt() - max(1, tileHeight / 110)
            val boardWidth = (width - 1) * xStep + xStep / 2 + tileWidth
            val boardHeight = (height - 1) * yStep + tileHeight
            if (max(boardWidth, boardHeight) <= BOARD_TARGET) return TileGeometry(tileWidth, tileHeight, xStep, yStep, boardWidth, boardHeight)
        }
        error("Map is too large for replay projection")
    }

    private data class TileGeometry(val tileWidth: Int, val tileHeight: Int, val xStep: Int, val yStep: Int, val boardWidth: Int, val boardHeight: Int)
    private data class Interpolation(val from: HexCoord, val to: HexCoord, val fraction: Double)
    private data class ReplayShot(val from: HexCoord, val to: HexCoord, val hit: Boolean, val blue: Boolean)
    private data class ReplayUnit(val id: String, val code: String, val team: ReplayTeam, var position: HexCoord, val quantity: Int, val initialPower: Long, var power: Long, var routed: Boolean = false)
    private data class ReplayObjective(var owner: ReplayTeam? = null, var progressTeam: ReplayTeam? = null, var progress: Int = 0)
    private enum class ReplayTeam { BLUE, RED }

    companion object {
        private const val SOURCE_SIZE = 1536
        private const val BOARD_TARGET = 1260
        private val BLUE = Color(38, 154, 255)
        private val BLUE_LIGHT = Color(94, 200, 255)
        private val RED = Color(228, 68, 71)
        private val RED_LIGHT = Color(255, 111, 86)
        private val GOLD = Color(242, 185, 62)
    }
}
