package com.tggames.frontline.campaign

import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.DeploymentEntry
import com.tggames.frontline.battle.HexCoord
import com.tggames.frontline.battle.Tactic
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

data class WeeklyUnitContribution(
    val code: String,
    val level: Int,
    val quantity: Int,
    val contributorPlayerId: Long? = null,
    val sourceContributionId: Long? = null,
    val entryId: String? = null,
    val tactic: Tactic = Tactic.MANEUVER,
    val primaryObjectiveId: String? = null,
)
data class AllianceForce(val code: String, val units: List<WeeklyUnitContribution>, val contributors: Int, val contributedPower: Long)

data class WeeklyContributionPerformance(
    val playerId: Long,
    val destroyedPower: Long,
    val capturedObjectives: Int,
)

data class WeeklyBalance(
    val maxTicks: Int = 96,
    val objectiveBasePoints: Int = 1_000,
    val objectiveDecayPerTick: Int = 15,
    val objectiveMinPoints: Int = 200,
    val survivorScorePercent: Int = 50,
    val npcMinCp: Int = 10,
    val npcMaxCp: Int = 25,
)

enum class WeeklySide { A, B }
enum class WeeklyEndReason { ALL_OBJECTIVES_CAPTURED, ARMY_DESTROYED, TIME_LIMIT }
enum class WeeklyEventType { FORMATION_MOVED, FORMATION_HIT, OBJECTIVE_PROGRESS, OBJECTIVE_CAPTURED, OBJECTIVE_LOST, FORMATION_DESTROYED }
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
    val contributorPlayerIds: List<Long> = emptyList(),
    val destroyedPower: Long = 0,
    val formationId: String? = null,
    val targetFormationId: String? = null,
    val unitCode: String? = null,
    val targetUnitCode: String? = null,
    val from: HexCoord? = null,
    val to: HexCoord? = null,
    val amount: Long = 0,
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
    val contributorPlayerId: Long? = null,
    val id: String = "",
    val initialPosition: HexCoord? = null,
    val sourceContributionId: Long? = null,
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
    val contributionPerformance: List<WeeklyContributionPerformance> = emptyList(),
)

@Component
class WeeklyBattleEngine(private val equipment: EquipmentCatalog) {
    fun resolve(
        serverSalt: String,
        weekKey: String,
        pairIndex: Int,
        map: BattleMapDefinition,
        forceA: AllianceForce,
        forceB: AllianceForce,
        balance: WeeklyBalance,
        engineVersion: Int = CURRENT_ENGINE_VERSION,
    ): WeeklyBattleResult {
        require(forceA.code != forceB.code)
        require(balance.maxTicks > 0)
        require(engineVersion in LEGACY_ENGINE_VERSION..CURRENT_ENGINE_VERSION)
        val seed = deriveSeed(serverSalt, "$weekKey:$pairIndex:${map.id}:${forceA.code}:${forceB.code}")
        val random = Random(seed)
        val npcA = npcSquad(random, balance)
        val npcB = npcSquad(random, balance)
        val unitsA = forceA.units + npcA.units
        val unitsB = forceB.units + npcB.units
        val effectiveA = unitsA.sumOf(::unitPower)
        val effectiveB = unitsB.sumOf(::unitPower)
        val formations = (deploy(WeeklySide.A, unitsA, map.playerEntries) + deploy(WeeklySide.B, unitsB, map.enemyEntries)).toMutableList()
        val objectives = map.objectives.associate { it.id to ObjectiveState(it.id, it.position, it.captureSteps) }.toMutableMap()
        val events = mutableListOf<WeeklyBattleEvent>()
        var completedTicks = 0
        var endReason = WeeklyEndReason.TIME_LIMIT

        for (tick in 1..balance.maxTicks) {
            completedTicks = tick
            moveFormations(formations, objectives.values.toList(), map, tick, events)
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
        val winnerSide = winner(engineVersion, endReason, remainingA, remainingB, objectiveA, objectiveB, scoreA, scoreB, random)
        val contributorIds = (forceA.units + forceB.units).mapNotNull { it.contributorPlayerId }.distinct().sorted()
        val contributionPerformance = contributorIds.map { playerId ->
            WeeklyContributionPerformance(
                playerId = playerId,
                destroyedPower = events.filter {
                    it.type == WeeklyEventType.FORMATION_DESTROYED && playerId in it.contributorPlayerIds
                }.sumOf { it.destroyedPower },
                capturedObjectives = events.count {
                    it.type == WeeklyEventType.OBJECTIVE_CAPTURED && playerId in it.contributorPlayerIds
                },
            )
        }

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
            contributionPerformance = contributionPerformance,
        )
    }

    internal fun capturePoints(tick: Int, balance: WeeklyBalance): Long =
        max(balance.objectiveMinPoints, balance.objectiveBasePoints - tick * balance.objectiveDecayPerTick).toLong()

    private fun deploy(side: WeeklySide, units: List<WeeklyUnitContribution>, entries: List<DeploymentEntry>): List<FormationState> =
        units.groupBy { FormationKey(it.sourceContributionId, it.contributorPlayerId, it.code, it.level, it.entryId, it.tactic, it.primaryObjectiveId) }.entries
            .sortedWith(compareBy({ it.key.contributorPlayerId ?: Long.MIN_VALUE }, { it.key.sourceContributionId ?: Long.MIN_VALUE }, { it.key.code }, { it.key.level }))
            .mapIndexed { index, (key, members) ->
            val contributorPlayerId = key.contributorPlayerId
            val code = key.code
            val level = key.level
            val definition = equipment.require(code)
            val quantity = members.sumOf { it.quantity }
            val unit = WeeklyUnitContribution(code, level, quantity, contributorPlayerId, key.sourceContributionId, key.entryId, key.tactic, key.primaryObjectiveId)
            val power = unitPower(unit)
            val entry = entries.firstOrNull { it.id == key.entryId } ?: entries[index % entries.size]
            FormationState(
                id = "${side.name.lowercase()}:$index:${key.sourceContributionId ?: contributorPlayerId ?: "npc"}:$code:$level",
                side = side,
                contributorPlayerId = contributorPlayerId,
                sourceContributionId = key.sourceContributionId,
                type = formationType(code),
                unitCode = code,
                level = level,
                quantity = quantity,
                position = entry.position,
                initialPosition = entry.position,
                initialPower = power,
                power = power,
                movement = max(1, definition.spatial.movementPoints / 2),
                weaponRange = definition.spatial.weaponRange,
                attackPercent = 70 + definition.stats.scaled(level).attack * 2,
                armor = definition.stats.scaled(level).armor,
                profile = definition.spatial.movementProfile,
                fireMode = definition.spatial.fireMode,
                tactic = key.tactic,
                primaryObjectiveId = key.primaryObjectiveId,
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

    private fun moveFormations(
        formations: List<FormationState>,
        objectives: List<ObjectiveState>,
        map: BattleMapDefinition,
        tick: Int,
        events: MutableList<WeeklyBattleEvent>,
    ) {
        formations.filter { it.power > 0 }.forEach { unit ->
            if (unit.tactic == Tactic.DEFENSE && objectives.any { it.owner == unit.side && it.position == unit.position }) return@forEach
            val candidates = objectives.filter { it.owner != unit.side }.ifEmpty { objectives }
            val goal = candidates.firstOrNull { it.id == unit.primaryObjectiveId } ?: when (unit.tactic) {
                Tactic.ASSAULT -> candidates.minWithOrNull(compareBy<ObjectiveState> { map.distanceBetween(unit.position, it.position) }.thenBy { it.id })
                Tactic.DEFENSE -> candidates.minWithOrNull(compareBy<ObjectiveState> { map.distanceBetween(unit.position, it.position) }.thenByDescending { it.captureSteps })
                Tactic.AMBUSH -> candidates.maxWithOrNull(compareBy<ObjectiveState> { map.terrainAt(it.position).cover }.thenByDescending { -map.distanceBetween(unit.position, it.position) })
                Tactic.MANEUVER -> candidates.minWithOrNull(compareBy<ObjectiveState> { routeCost(unit.position, it.position, unit.profile, map, Tactic.MANEUVER) }.thenBy { it.id })
                Tactic.RECON -> candidates.minWithOrNull(compareBy<ObjectiveState> { map.distanceBetween(unit.position, it.position) }.thenBy { it.captureSteps })
            } ?: return@forEach
            val from = unit.position
            repeat(unit.movement) { shortestNextStep(unit.position, goal.position, unit.profile, map, unit.tactic)?.let { unit.position = it } }
            if (unit.position != from) {
                events += WeeklyBattleEvent(
                    tick = tick,
                    type = WeeklyEventType.FORMATION_MOVED,
                    side = unit.side,
                    formationType = unit.type,
                    formationId = unit.id,
                    unitCode = unit.unitCode,
                    from = from,
                    to = unit.position,
                )
            }
        }
    }

    private fun fire(side: WeeklySide, formations: List<FormationState>, map: BattleMapDefinition, random: Random, tick: Int, events: MutableList<WeeklyBattleEvent>) {
        formations.filter { it.side == side && it.power > 0 }.sortedBy { it.type.ordinal }.forEach { shooter ->
            val targetComparator = when (shooter.tactic) {
                Tactic.ASSAULT -> compareBy<FormationState> { it.power * 100 / it.initialPower.coerceAtLeast(1) }.thenBy { map.distanceBetween(shooter.position, it.position) }
                Tactic.DEFENSE -> compareBy<FormationState> { map.distanceBetween(shooter.position, it.position) }.thenByDescending { it.power }
                Tactic.AMBUSH -> compareByDescending<FormationState> { it.initialPower }.thenBy { map.distanceBetween(shooter.position, it.position) }
                Tactic.MANEUVER -> compareBy<FormationState> { if (it.type in setOf(WeeklyFormationType.ARTILLERY, WeeklyFormationType.SUPPORT)) 0 else 1 }.thenBy { map.distanceBetween(shooter.position, it.position) }
                Tactic.RECON -> compareBy<FormationState> { if (it.type == WeeklyFormationType.RECON) 0 else 1 }.thenBy { map.distanceBetween(shooter.position, it.position) }
            }
            val target = formations.filter { it.side != side && it.power > 0 && canAttack(shooter, it, map) }
                .minWithOrNull(targetComparator) ?: return@forEach
            val base = max(1L, shooter.power * shooter.attackPercent / 1000)
            val protection = map.terrainAt(target.position).cover + target.armor / 12
            val damage = max(1L, base * random.nextInt(85, 116) / 100 * max(3, 10 - protection) / 10)
            val before = target.power
            target.power = (target.power - damage).coerceAtLeast(0)
            events += WeeklyBattleEvent(
                tick = tick,
                type = WeeklyEventType.FORMATION_HIT,
                side = side,
                formationType = shooter.type,
                formationId = shooter.id,
                targetFormationId = target.id,
                unitCode = shooter.unitCode,
                targetUnitCode = target.unitCode,
                from = shooter.position,
                to = target.position,
                amount = damage,
            )
            if (before > 0 && target.power == 0L) events += WeeklyBattleEvent(
                tick = tick,
                type = WeeklyEventType.FORMATION_DESTROYED,
                side = side,
                formationType = target.type,
                contributorPlayerIds = listOfNotNull(shooter.contributorPlayerId),
                destroyedPower = target.initialPower,
                formationId = shooter.id,
                targetFormationId = target.id,
                unitCode = shooter.unitCode,
                targetUnitCode = target.unitCode,
                from = shooter.position,
                to = target.position,
            )
        }
    }

    private fun canAttack(shooter: FormationState, target: FormationState, map: BattleMapDefinition): Boolean {
        if (map.distanceBetween(shooter.position, target.position) !in 1..shooter.weaponRange) return false
        val targetIsAir = target.profile == MovementProfile.AIR
        val validTarget = when (shooter.fireMode) {
            FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE -> targetIsAir
            FireMode.AIR_TO_GROUND, FireMode.DIRECT, FireMode.INDIRECT -> !targetIsAir
        }
        if (!validTarget) return false
        if (shooter.fireMode == FireMode.INDIRECT || shooter.profile == MovementProfile.AIR) return true
        return map.lineBetween(shooter.position, target.position).drop(1).dropLast(1).none { map.terrainAt(it).blocksLineOfSight }
    }

    private fun capture(formations: List<FormationState>, objectives: Collection<ObjectiveState>, tick: Int, balance: WeeklyBalance, events: MutableList<WeeklyBattleEvent>) {
        objectives.forEach { objective ->
            val occupiers = formations.filter { it.power > 0 && it.type != WeeklyFormationType.AIR && it.position == objective.position }
            val occupyingSides = occupiers.map { it.side }.distinct()
            if (occupyingSides.size != 1) { objective.progressSide = null; objective.progress = 0; return@forEach }
            val side = occupyingSides.single()
            if (objective.owner == side) return@forEach
            if (objective.progressSide != side) { objective.progressSide = side; objective.progress = 0 }
            objective.progress++
            events += WeeklyBattleEvent(
                tick = tick,
                type = WeeklyEventType.OBJECTIVE_PROGRESS,
                side = side,
                objectiveId = objective.id,
                amount = objective.progress.toLong(),
            )
            if (objective.progress >= objective.captureSteps) {
                objective.owner?.let { events += WeeklyBattleEvent(tick = tick, type = WeeklyEventType.OBJECTIVE_LOST, side = it, objectiveId = objective.id) }
                objective.owner = side
                objective.points = capturePoints(tick, balance)
                objective.capturedAtTick = tick
                objective.progressSide = null
                objective.progress = 0
                events += WeeklyBattleEvent(
                    tick = tick,
                    type = WeeklyEventType.OBJECTIVE_CAPTURED,
                    side = side,
                    objectiveId = objective.id,
                    awardedPoints = objective.points,
                    contributorPlayerIds = occupiers.filter { it.side == side }.mapNotNull { it.contributorPlayerId }.distinct().sorted(),
                )
            }
        }
    }

    private fun shortestNextStep(start: HexCoord, goal: HexCoord, profile: MovementProfile, map: BattleMapDefinition, tactic: Tactic): HexCoord? {
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
                val terrain = map.terrainAt(next)
                val baseStep = terrain.movementCost(profile) ?: return@forEach
                val step = when (tactic) {
                    Tactic.AMBUSH -> (baseStep - terrain.cover).coerceAtLeast(1)
                    Tactic.MANEUVER -> baseStep + if (terrain.name == "ROAD") 0 else 1
                    else -> baseStep
                }
                val candidate = currentCost + step
                if (candidate < (costs[next] ?: Int.MAX_VALUE)) { costs[next] = candidate; previous[next] = current; queue += next to candidate }
            }
        }
        if (goal !in previous) return null
        var cursor = goal
        while (previous[cursor] != start) cursor = previous[cursor] ?: return null
        return cursor
    }

    private fun routeCost(start: HexCoord, goal: HexCoord, profile: MovementProfile, map: BattleMapDefinition, tactic: Tactic): Int {
        var current = start
        var cost = 0
        repeat(map.width * map.height) {
            val next = shortestNextStep(current, goal, profile, map, tactic) ?: return if (current == goal) cost else Int.MAX_VALUE
            cost += map.terrainAt(next).movementCost(profile) ?: return Int.MAX_VALUE
            current = next
            if (current == goal) return cost
        }
        return Int.MAX_VALUE
    }

    internal fun winner(
        engineVersion: Int,
        reason: WeeklyEndReason,
        remainingA: Long,
        remainingB: Long,
        objectiveA: Long,
        objectiveB: Long,
        scoreA: Long,
        scoreB: Long,
        random: Random,
    ): WeeklySide {
        val ordered = if (engineVersion <= LEGACY_ENGINE_VERSION) {
            if (reason == WeeklyEndReason.TIME_LIMIT) {
                listOf(remainingA to remainingB, objectiveA to objectiveB, scoreA to scoreB)
            } else {
                listOf(scoreA to scoreB, remainingA to remainingB, objectiveA to objectiveB)
            }
        } else {
            when (reason) {
                WeeklyEndReason.TIME_LIMIT -> listOf(scoreA to scoreB, objectiveA to objectiveB, remainingA to remainingB)
                WeeklyEndReason.ARMY_DESTROYED -> listOf(remainingA to remainingB, scoreA to scoreB, objectiveA to objectiveB)
                WeeklyEndReason.ALL_OBJECTIVES_CAPTURED -> listOf(objectiveA to objectiveB, scoreA to scoreB, remainingA to remainingB)
            }
        }
        ordered.firstOrNull { it.first != it.second }?.let { return if (it.first > it.second) WeeklySide.A else WeeklySide.B }
        return if (random.nextBoolean()) WeeklySide.A else WeeklySide.B
    }

    companion object {
        const val LEGACY_ENGINE_VERSION = 7
        const val CURRENT_ENGINE_VERSION = 8
    }

    private fun deriveSeed(serverSalt: String, key: String): Long {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return ByteBuffer.wrap(mac.doFinal(key.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, Long.SIZE_BYTES)).long
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

private data class FormationState(
    val id: String,
    val side: WeeklySide,
    val contributorPlayerId: Long?,
    val sourceContributionId: Long?,
    val type: WeeklyFormationType,
    val unitCode: String,
    val level: Int,
    val quantity: Int,
    var position: HexCoord,
    val initialPosition: HexCoord,
    val initialPower: Long,
    var power: Long,
    val movement: Int,
    val weaponRange: Int,
    val attackPercent: Int,
    val armor: Int,
    val profile: MovementProfile,
    val fireMode: FireMode,
    val tactic: Tactic,
    val primaryObjectiveId: String?,
) {
    fun result() = WeeklyFormationResult(
        side = side,
        type = type,
        unitCode = unitCode,
        level = level,
        quantity = quantity,
        position = position,
        weaponRange = weaponRange,
        initialPower = initialPower,
        remainingPower = power,
        contributorPlayerId = contributorPlayerId,
        id = id,
        initialPosition = initialPosition,
        sourceContributionId = sourceContributionId,
    )
}

private data class FormationKey(
    val sourceContributionId: Long?,
    val contributorPlayerId: Long?,
    val code: String,
    val level: Int,
    val entryId: String?,
    val tactic: Tactic,
    val primaryObjectiveId: String?,
)

private data class NpcSquad(val cp: Int, val units: List<WeeklyUnitContribution>)

private data class ObjectiveState(
    val id: String, val position: HexCoord, val captureSteps: Int,
    var owner: WeeklySide? = null, var progressSide: WeeklySide? = null, var progress: Int = 0,
    var points: Long = 0, var capturedAtTick: Int? = null,
) {
    fun result() = WeeklyObjectiveResult(id, owner, points, capturedAtTick)
}
