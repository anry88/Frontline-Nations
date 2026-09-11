package com.tggames.frontline.campaign

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.HexCoord
import com.tggames.frontline.catalog.MovementProfile
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.catalog.FireMode
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.PriorityQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.random.Random

data class WeeklyUnitContribution(val code: String, val level: Int, val quantity: Int)
data class AllianceForce(val code: String, val units: List<WeeklyUnitContribution>, val contributors: Int, val contributedPower: Long)

data class WeeklyBalance(
    val maxTicks: Int = 48,
    val objectiveBasePoints: Int = 1_000,
    val objectiveDecayPerTick: Int = 15,
    val objectiveMinPoints: Int = 200,
    val survivorScorePercent: Int = 50,
    val npcMinCp: Int = 10,
    val npcMaxCp: Int = 25,
)

enum class WeeklySide { A, B }
enum class WeeklyEndReason { ALL_OBJECTIVES_CAPTURED, ARMY_DESTROYED, TIME_LIMIT }
enum class WeeklyEventType { OBJECTIVE_CAPTURED, OBJECTIVE_LOST, FORMATION_DESTROYED }
enum class WeeklyFormationType { ARMOR, ARTILLERY, RECON, AIR, SUPPORT }

data class WeeklyBattleEvent(
    val phase: String = "",
    val text: String = "",
    val scoreA: Long = 0,
    val scoreB: Long = 0,
    val tick: Int = 0,
    val type: WeeklyEventType? = null,
    val side: WeeklySide? = null,
    val objectiveId: String? = null,
    val formationType: WeeklyFormationType? = null,
    val awardedPoints: Long = 0,
)

data class WeeklyFormationResult(
    val side: WeeklySide,
    val type: WeeklyFormationType,
    val unitCode: String,
    val level: Int,
    val quantity: Int,
    val position: HexCoord,
    val weaponRange: Int,
    val initialPower: Long,
    val remainingPower: Long,
)
data class WeeklyObjectiveResult(val id: String, val owner: WeeklySide?, val retainedPoints: Long, val capturedAtTick: Int?)

data class WeeklyBattleResult(
    val allianceA: String,
    val allianceB: String,
    val map: BattleMapDefinition,
    val effectivePowerA: Long,
    val effectivePowerB: Long,
    val npcBonusA: Int,
    val npcBonusB: Int,
    val scoreA: Long,
    val scoreB: Long,
    val objectiveScoreA: Long,
    val objectiveScoreB: Long,
    val destroyedScoreA: Long,
    val destroyedScoreB: Long,
    val survivorScoreA: Long,
    val survivorScoreB: Long,
    val remainingPowerA: Long,
    val remainingPowerB: Long,
    val winnerCode: String,
    val endReason: WeeklyEndReason,
    val completedTicks: Int,
    val seed: Long,
    val seedHash: String,
    val formations: List<WeeklyFormationResult>,
    val objectives: List<WeeklyObjectiveResult>,
    val events: List<WeeklyBattleEvent>,
    val npcUnitsA: List<WeeklyUnitContribution>,
    val npcUnitsB: List<WeeklyUnitContribution>,
)

@Component
class WeeklyBattleEngine(private val equipment: EquipmentCatalog) {
    fun resolve(serverSalt: String, weekKey: String, pairIndex: Int, map: BattleMapDefinition, forceA: AllianceForce, forceB: AllianceForce, balance: WeeklyBalance): WeeklyBattleResult {
        require(forceA.code != forceB.code)
        require(balance.maxTicks > 0)
        val seed = deriveSeed(serverSalt, "$weekKey:$pairIndex:${map.id}:${forceA.code}:${forceB.code}")
        val random = Random(seed)
        val npcA = npcSquad(random, balance)
        val npcB = npcSquad(random, balance)
        val unitsA = forceA.units + npcA.units
        val unitsB = forceB.units + npcB.units
        val effectiveA = unitsA.sumOf(::unitPower)
        val effectiveB = unitsB.sumOf(::unitPower)
        val formations = (deploy(WeeklySide.A, unitsA, map.playerEntries.map { it.position }) + deploy(WeeklySide.B, unitsB, map.enemyEntries.map { it.position })).toMutableList()
        val objectives = map.objectives.associate { it.id to ObjectiveState(it.id, it.position, it.captureSteps) }.toMutableMap()
        val events = mutableListOf<WeeklyBattleEvent>()
        var completedTicks = 0
        var endReason = WeeklyEndReason.TIME_LIMIT

        for (tick in 1..balance.maxTicks) {
            completedTicks = tick
            moveFormations(formations, objectives.values.toList(), map)
            fire(WeeklySide.A, formations, map, random, tick, events)
            fire(WeeklySide.B, formations, map, random, tick, events)
            capture(formations, objectives.values, tick, balance, events)
            val aliveA = formations.any { it.side == WeeklySide.A && it.power > 0 }
            val aliveB = formations.any { it.side == WeeklySide.B && it.power > 0 }
            if (!aliveA || !aliveB) { endReason = WeeklyEndReason.ARMY_DESTROYED; break }
            val owners = objectives.values.map { it.owner }.distinct()
            if (owners.size == 1 && owners.single() != null) { endReason = WeeklyEndReason.ALL_OBJECTIVES_CAPTURED; break }
        }

        val remainingA = formations.filter { it.side == WeeklySide.A }.sumOf { it.power }
        val remainingB = formations.filter { it.side == WeeklySide.B }.sumOf { it.power }
        val objectiveA = objectives.values.filter { it.owner == WeeklySide.A }.sumOf { it.points }
        val objectiveB = objectives.values.filter { it.owner == WeeklySide.B }.sumOf { it.points }
        val destroyedA = (effectiveB - remainingB).coerceAtLeast(0)
        val destroyedB = (effectiveA - remainingA).coerceAtLeast(0)
        val survivorA = remainingA * balance.survivorScorePercent / 100
        val survivorB = remainingB * balance.survivorScorePercent / 100
        val scoreA = objectiveA + destroyedA + survivorA
        val scoreB = objectiveB + destroyedB + survivorB
        val winnerSide = winner(endReason, remainingA, remainingB, objectiveA, objectiveB, scoreA, scoreB, random)

        return WeeklyBattleResult(
            allianceA = forceA.code, allianceB = forceB.code, map = map,
            effectivePowerA = effectiveA, effectivePowerB = effectiveB, npcBonusA = npcA.cp, npcBonusB = npcB.cp,
            scoreA = scoreA, scoreB = scoreB, objectiveScoreA = objectiveA, objectiveScoreB = objectiveB,
            destroyedScoreA = destroyedA, destroyedScoreB = destroyedB, survivorScoreA = survivorA, survivorScoreB = survivorB,
            remainingPowerA = remainingA, remainingPowerB = remainingB,
            winnerCode = if (winnerSide == WeeklySide.A) forceA.code else forceB.code,
            endReason = endReason, completedTicks = completedTicks, seed = seed,
            seedHash = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array()).toHex(),
            formations = formations.map { it.result() }, objectives = objectives.values.map { it.result() }, events = events,
            npcUnitsA = npcA.units, npcUnitsB = npcB.units,
        )
    }

    internal fun capturePoints(tick: Int, balance: WeeklyBalance): Long =
        max(balance.objectiveMinPoints, balance.objectiveBasePoints - tick * balance.objectiveDecayPerTick).toLong()

    private fun deploy(side: WeeklySide, units: List<WeeklyUnitContribution>, entries: List<HexCoord>): List<FormationState> =
        units.groupBy { it.code to it.level }.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).mapIndexed { index, (key, members) ->
            val (code, level) = key
            val definition = equipment.require(code)
            val quantity = members.sumOf { it.quantity }
            val unit = WeeklyUnitContribution(code, level, quantity)
            val power = unitPower(unit)
            FormationState(
                side = side,
                type = formationType(code),
                unitCode = code,
                level = level,
                quantity = quantity,
                position = entries[index % entries.size],
                initialPower = power,
                power = power,
                movement = max(1, definition.spatial.movementPoints / 2),
                weaponRange = definition.spatial.weaponRange,
                attackPercent = 70 + definition.stats.scaled(level).attack * 2,
                armor = definition.stats.scaled(level).armor,
                profile = definition.spatial.movementProfile,
                fireMode = definition.spatial.fireMode,
            )
        }

    private fun npcSquad(random: Random, balance: WeeklyBalance): NpcSquad {
        require(balance.npcMinCp in 1..balance.npcMaxCp)
        val targetCp = random.nextInt(balance.npcMinCp, balance.npcMaxCp + 1)
        var remaining = targetCp
        val selected = mutableListOf<String>()
        while (remaining > 0) {
            val candidates = equipment.units.filter { it.cpCost <= remaining }
            val unit = candidates[random.nextInt(candidates.size)]
            selected += unit.code
            remaining -= unit.cpCost
        }
        return NpcSquad(targetCp, selected.groupingBy { it }.eachCount().map { WeeklyUnitContribution(it.key, 1, it.value) })
    }

    private fun unitPower(unit: WeeklyUnitContribution): Long {
        val definition = equipment.require(unit.code)
        val levelPercent = 100 + (unit.level.coerceIn(1, 5) - 1) * 12
        return definition.cpCost.toLong() * unit.quantity * 100 * levelPercent / 100
    }

    private fun formationType(code: String): WeeklyFormationType = when (code) {
        "MBT", "LIGHT_ARMOR" -> WeeklyFormationType.ARMOR
        "ARTILLERY" -> WeeklyFormationType.ARTILLERY
        "RECON_VEHICLE" -> WeeklyFormationType.RECON
        "ATTACK_AIRCRAFT", "FIGHTER" -> WeeklyFormationType.AIR
        "AIR_DEFENSE" -> WeeklyFormationType.SUPPORT
        else -> error("No weekly formation type for $code")
    }

    private fun moveFormations(formations: List<FormationState>, objectives: List<ObjectiveState>, map: BattleMapDefinition) {
        formations.filter { it.power > 0 }.forEach { unit ->
            val candidates = objectives.filter { it.owner != unit.side }.ifEmpty { objectives }
            val goal = candidates.minWithOrNull(compareBy<ObjectiveState> { unit.position.distanceTo(it.position) }.thenBy { it.id }) ?: return@forEach
            repeat(unit.movement) { shortestNextStep(unit.position, goal.position, unit.profile, map)?.let { unit.position = it } }
        }
    }

    private fun fire(side: WeeklySide, formations: List<FormationState>, map: BattleMapDefinition, random: Random, tick: Int, events: MutableList<WeeklyBattleEvent>) {
        formations.filter { it.side == side && it.power > 0 }.sortedBy { it.type.ordinal }.forEach { shooter ->
            val target = formations.filter { it.side != side && it.power > 0 && canAttack(shooter, it, map) }
                .minWithOrNull(compareBy<FormationState> { shooter.position.distanceTo(it.position) }.thenByDescending { it.power }) ?: return@forEach
            val base = max(1L, shooter.power * shooter.attackPercent / 1000)
            val protection = map.terrainAt(target.position).cover + target.armor / 12
            val damage = max(1L, base * random.nextInt(85, 116) / 100 * max(3, 10 - protection) / 10)
            val before = target.power
            target.power = (target.power - damage).coerceAtLeast(0)
            if (before > 0 && target.power == 0L) events += WeeklyBattleEvent(tick = tick, type = WeeklyEventType.FORMATION_DESTROYED, side = side, formationType = target.type)
        }
    }

    private fun canAttack(shooter: FormationState, target: FormationState, map: BattleMapDefinition): Boolean {
        if (shooter.position.distanceTo(target.position) !in 1..shooter.weaponRange) return false
        val targetIsAir = target.profile == MovementProfile.AIR
        val validTarget = when (shooter.fireMode) {
            FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE -> targetIsAir
            FireMode.AIR_TO_GROUND, FireMode.DIRECT, FireMode.INDIRECT -> !targetIsAir
        }
        if (!validTarget) return false
        if (shooter.fireMode == FireMode.INDIRECT || shooter.profile == MovementProfile.AIR) return true
        return hexLine(shooter.position, target.position).drop(1).dropLast(1).none { map.terrainAt(it).blocksLineOfSight }
    }

    private fun capture(formations: List<FormationState>, objectives: Collection<ObjectiveState>, tick: Int, balance: WeeklyBalance, events: MutableList<WeeklyBattleEvent>) {
        objectives.forEach { objective ->
            val occupiers = formations.filter { it.power > 0 && it.type != WeeklyFormationType.AIR && it.position == objective.position }.map { it.side }.distinct()
            if (occupiers.size != 1) { objective.progressSide = null; objective.progress = 0; return@forEach }
            val side = occupiers.single()
            if (objective.owner == side) return@forEach
            if (objective.progressSide != side) { objective.progressSide = side; objective.progress = 0 }
            objective.progress++
            if (objective.progress >= objective.captureSteps) {
                objective.owner?.let { events += WeeklyBattleEvent(tick = tick, type = WeeklyEventType.OBJECTIVE_LOST, side = it, objectiveId = objective.id) }
                objective.owner = side
                objective.points = capturePoints(tick, balance)
                objective.capturedAtTick = tick
                objective.progressSide = null
                objective.progress = 0
                events += WeeklyBattleEvent(tick = tick, type = WeeklyEventType.OBJECTIVE_CAPTURED, side = side, objectiveId = objective.id, awardedPoints = objective.points)
            }
        }
    }

    private fun shortestNextStep(start: HexCoord, goal: HexCoord, profile: MovementProfile, map: BattleMapDefinition): HexCoord? {
        if (start == goal) return null
        val queue = PriorityQueue(compareBy<Pair<HexCoord, Int>> { it.second }.thenBy { it.first.q }.thenBy { it.first.r })
        val costs = mutableMapOf(start to 0)
        val previous = mutableMapOf<HexCoord, HexCoord>()
        queue += start to 0
        while (queue.isNotEmpty()) {
            val (current, currentCost) = queue.remove()
            if (current == goal) break
            if (currentCost != costs[current]) continue
            map.neighbors(current).forEach { next ->
                val step = map.terrainAt(next).movementCost(profile) ?: return@forEach
                val candidate = currentCost + step
                if (candidate < (costs[next] ?: Int.MAX_VALUE)) { costs[next] = candidate; previous[next] = current; queue += next to candidate }
            }
        }
        if (goal !in previous) return null
        var cursor = goal
        while (previous[cursor] != start) cursor = previous[cursor] ?: return null
        return cursor
    }

    private fun winner(reason: WeeklyEndReason, remainingA: Long, remainingB: Long, objectiveA: Long, objectiveB: Long, scoreA: Long, scoreB: Long, random: Random): WeeklySide {
        val ordered = if (reason == WeeklyEndReason.TIME_LIMIT) listOf(remainingA to remainingB, objectiveA to objectiveB, scoreA to scoreB)
            else listOf(scoreA to scoreB, remainingA to remainingB, objectiveA to objectiveB)
        ordered.firstOrNull { it.first != it.second }?.let { return if (it.first > it.second) WeeklySide.A else WeeklySide.B }
        return if (random.nextBoolean()) WeeklySide.A else WeeklySide.B
    }

    private fun hexLine(from: HexCoord, to: HexCoord): List<HexCoord> {
        val count = from.distanceTo(to)
        if (count == 0) return listOf(from)
        fun cube(coord: HexCoord) = Triple(coord.q.toDouble(), (-coord.q - coord.r).toDouble(), coord.r.toDouble())
        val a = cube(from); val b = cube(to)
        return (0..count).map { i ->
            val t = i.toDouble() / count
            val x = a.first + (b.first - a.first) * t; val y = a.second + (b.second - a.second) * t; val z = a.third + (b.third - a.third) * t
            var rx = kotlin.math.round(x); var ry = kotlin.math.round(y); var rz = kotlin.math.round(z)
            val dx = kotlin.math.abs(rx - x); val dy = kotlin.math.abs(ry - y); val dz = kotlin.math.abs(rz - z)
            if (dx > dy && dx > dz) rx = -ry - rz else if (dy > dz) ry = -rx - rz else rz = -rx - ry
            HexCoord(rx.toInt(), rz.toInt())
        }
    }

    private fun deriveSeed(serverSalt: String, key: String): Long {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return ByteBuffer.wrap(mac.doFinal(key.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, Long.SIZE_BYTES)).long
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

private data class FormationState(
    val side: WeeklySide,
    val type: WeeklyFormationType,
    val unitCode: String,
    val level: Int,
    val quantity: Int,
    var position: HexCoord,
    val initialPower: Long,
    var power: Long,
    val movement: Int,
    val weaponRange: Int,
    val attackPercent: Int,
    val armor: Int,
    val profile: MovementProfile,
    val fireMode: FireMode,
) {
    fun result() = WeeklyFormationResult(side, type, unitCode, level, quantity, position, weaponRange, initialPower, power)
}

private data class NpcSquad(val cp: Int, val units: List<WeeklyUnitContribution>)

private data class ObjectiveState(
    val id: String, val position: HexCoord, val captureSteps: Int,
    var owner: WeeklySide? = null, var progressSide: WeeklySide? = null, var progress: Int = 0,
    var points: Long = 0, var capturedAtTick: Int? = null,
) {
    fun result() = WeeklyObjectiveResult(id, owner, points, capturedAtTick)
}
