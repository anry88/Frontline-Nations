package com.tggames.frontline.battle

import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.catalog.FireMode
import com.tggames.frontline.catalog.MovementProfile
import com.tggames.frontline.progression.ForceTier
import com.tggames.frontline.progression.ForceTierCatalog
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import java.util.UUID
import kotlin.random.Random

enum class BattleSide { PLAYER, ENEMY }

enum class SpatialEndReason { ALL_OBJECTIVES_CAPTURED, ARMY_DESTROYED, ARMY_ROUTED }

enum class SpatialEventType {
    UNIT_MOVED,
    SHOT_MISSED,
    UNIT_HIT,
    UNIT_DESTROYED,
    CAPTURE_PROGRESS,
    OBJECTIVE_CAPTURED,
    UNIT_ROUTED,
}

data class DeploymentPlan(
    val entryId: String,
    val objectiveId: String,
)

data class SpatialBattleEvent(
    val step: Int,
    val type: SpatialEventType,
    val side: BattleSide,
    val unitId: UUID? = null,
    val unitCode: String? = null,
    val from: HexCoord? = null,
    val to: HexCoord? = null,
    val targetUnitId: UUID? = null,
    val targetUnitCode: String? = null,
    val objectiveId: String? = null,
    val amount: Int? = null,
)

data class SpatialUnitResult(
    val id: UUID,
    val code: String,
    val side: BattleSide,
    val position: HexCoord,
    val hitPoints: Int,
    val routed: Boolean,
    val quantity: Int,
    val remainingQuantity: Int,
)

data class ObjectiveResult(
    val id: String,
    val owner: BattleSide?,
    val progressSide: BattleSide?,
    val progress: Int,
    val captureSteps: Int,
)

data class ObjectiveCaptureUpdate(
    val side: BattleSide,
    val progress: Int,
    val captured: Boolean,
)

internal data class ObjectiveCaptureState(
    val definition: StrategicObjective,
    var owner: BattleSide? = null,
    var progressSide: BattleSide? = null,
    var progress: Int = 0,
) {
    fun advance(occupiers: Set<BattleSide>): ObjectiveCaptureUpdate? {
        if (occupiers.size != 1) {
            progressSide = null
            progress = 0
            return null
        }
        val side = occupiers.single()
        if (owner == side) {
            progressSide = null
            progress = 0
            return null
        }
        if (progressSide != side) {
            progressSide = side
            progress = 0
        }
        progress++
        val reportedProgress = progress
        val captured = progress >= definition.captureSteps
        if (captured) {
            owner = side
            progressSide = null
            progress = 0
        }
        return ObjectiveCaptureUpdate(side, reportedProgress, captured)
    }

    fun result() = ObjectiveResult(definition.id, owner, progressSide, progress, definition.captureSteps)
}

data class SpatialBattleResult(
    val map: BattleMapDefinition,
    val playerPlan: DeploymentPlan,
    val enemyEntryId: String,
    val enemyObjectiveId: String,
    val enemyTactic: Tactic,
    val enemyGroup: CombatGroupSnapshot,
    val winner: BattleSide,
    val endReason: SpatialEndReason,
    val steps: Int,
    val playerUnits: List<SpatialUnitResult>,
    val enemyUnits: List<SpatialUnitResult>,
    val objectives: List<ObjectiveResult>,
    val events: List<SpatialBattleEvent>,
)

@Component
class SpatialBattleEngine(
    private val equipment: EquipmentCatalog,
    private val forceTiers: ForceTierCatalog,
) {
    internal fun canAttack(
        shooter: UnitBattleSnapshot,
        shooterPosition: HexCoord,
        target: UnitBattleSnapshot,
        targetPosition: HexCoord,
        alliedObservers: List<Pair<UnitBattleSnapshot, HexCoord>>,
        map: BattleMapDefinition,
    ): Boolean {
        val shooterState = UnitState(shooter, BattleSide.PLAYER, shooterPosition, 100, routed = false)
        val targetState = UnitState(target, BattleSide.ENEMY, targetPosition, 100, routed = false)
        val allies = listOf(shooterState) + alliedObservers.map {
            UnitState(it.first, BattleSide.PLAYER, it.second, 100, routed = false)
        }
        return targetState in availableTargets(shooterState, listOf(targetState), allies, map)
    }

    fun simulate(
        seed: Long,
        commanderLevel: Int,
        operation: OperationOffer,
        tactic: Tactic,
        group: CombatGroupSnapshot,
        plan: DeploymentPlan,
        map: BattleMapDefinition,
    ): SpatialBattleResult {
        require(group.units.isNotEmpty()) { "Combat group cannot be empty" }
        val playerEntry = requireNotNull(map.playerEntries.firstOrNull { it.id == plan.entryId }) { "Unknown deployment entry" }
        require(map.objectives.any { it.id == plan.objectiveId }) { "Unknown primary objective" }
        val random = Random(seed xor SPATIAL_SEED_MASK)
        val enemyEntry = map.enemyEntries[random.nextInt(map.enemyEntries.size)]
        val enemyObjective = map.objectives[random.nextInt(map.objectives.size)]
        val enemyTactic = enemyTactic(operation.enemy)
        val forceTier = forceTiers.forDeployedCp(group.usedCp)
        val enemyGroup = enemyGroup(seed, commanderLevel, operation, group.usedCp, forceTier)
        val units = mutableListOf<UnitState>()
        units += deploy(group.units, BattleSide.PLAYER, playerEntry.position)
        units += deploy(enemyGroup.units, BattleSide.ENEMY, enemyEntry.position)
        val controls = map.objectives.associate { it.id to ObjectiveCaptureState(it) }.toMutableMap()
        val events = mutableListOf<SpatialBattleEvent>()

        var endReason: SpatialEndReason? = null
        var winner: BattleSide? = null
        var completedSteps = 0
        for (step in 1..MAX_STEPS) {
            completedSteps = step
            moveAll(step, units, controls, map, plan, enemyObjective.id, tactic, enemyTactic, random, events)
            fireAll(step, units, map, tactic, enemyTactic, random, events)
            updateObjectives(step, units, controls, events)

            val playerOperational = units.any { it.side == BattleSide.PLAYER && it.operational }
            val enemyOperational = units.any { it.side == BattleSide.ENEMY && it.operational }
            if (!playerOperational || !enemyOperational) {
                winner = if (playerOperational) BattleSide.PLAYER else BattleSide.ENEMY
                endReason = SpatialEndReason.ARMY_DESTROYED
                break
            }
            val objectiveOwner = controls.values.map { it.owner }.distinct().singleOrNull()
            if (objectiveOwner != null) {
                winner = objectiveOwner
                endReason = SpatialEndReason.ALL_OBJECTIVES_CAPTURED
                break
            }
        }

        if (winner == null) {
            winner = decideOperationalWinner(units, controls)
            endReason = SpatialEndReason.ARMY_ROUTED
            units.filter { it.side != winner && it.operational }.forEach {
                it.routed = true
                events += SpatialBattleEvent(completedSteps, SpatialEventType.UNIT_ROUTED, it.side, it.snapshot.id, it.snapshot.code, from = it.position)
            }
        }

        return SpatialBattleResult(
            map = map,
            playerPlan = plan,
            enemyEntryId = enemyEntry.id,
            enemyObjectiveId = enemyObjective.id,
            enemyTactic = enemyTactic,
            enemyGroup = enemyGroup,
            winner = winner,
            endReason = requireNotNull(endReason),
            steps = completedSteps,
            playerUnits = unitResults(units, BattleSide.PLAYER),
            enemyUnits = unitResults(units, BattleSide.ENEMY),
            objectives = controls.values.map { it.result() },
            events = events,
        )
    }

    private fun deploy(snapshots: List<UnitBattleSnapshot>, side: BattleSide, position: HexCoord): List<UnitState> =
        snapshots.map { UnitState(it, side, position, 100 * it.quantity, routed = false) }

    private fun enemyGroup(
        seed: Long,
        commanderLevel: Int,
        operation: OperationOffer,
        playerCp: Int,
        forceTier: ForceTier,
    ): CombatGroupSnapshot {
        val pattern = when (operation.enemy) {
            EnemyArchetype.ARMOR -> listOf("MBT", "MBT", "LIGHT_ARMOR", "RECON_VEHICLE")
            EnemyArchetype.ARTILLERY -> listOf("MBT", "ARTILLERY", "ARTILLERY", "RECON_VEHICLE")
            EnemyArchetype.FORTIFIED -> listOf("MBT", "ARTILLERY", "AIR_DEFENSE", "RECON_VEHICLE")
            EnemyArchetype.AMBUSH -> listOf("LIGHT_ARMOR", "LIGHT_ARMOR", "ARTILLERY", "RECON_VEHICLE")
            EnemyArchetype.MOBILE -> listOf("LIGHT_ARMOR", "LIGHT_ARMOR", "ATTACK_AIRCRAFT", "RECON_VEHICLE")
            EnemyArchetype.AIR -> listOf("ATTACK_AIRCRAFT", "FIGHTER", "MBT", "RECON_VEHICLE")
            EnemyArchetype.AIR_DEFENSE -> listOf("AIR_DEFENSE", "AIR_DEFENSE", "MBT", "ARTILLERY")
        }
        val strengthPercent = when (operation.difficulty) {
            Difficulty.SCOUTED -> 90
            Difficulty.STANDARD -> 100
            Difficulty.RISKY -> 112
        }
        val targetCp = (playerCp * strengthPercent / 100).coerceIn(forceTier.minCp, forceTier.maxCp)
        val codes = buildList {
            var remaining = targetCp
            var index = 0
            while (remaining > 0) {
                val preferred = pattern[index % pattern.size]
                val code = if (equipment.require(preferred).cpCost <= remaining) preferred else "RECON_VEHICLE"
                add(code)
                remaining -= equipment.require(code).cpCost
                index++
            }
        }
        val difficultyLevel = when (operation.difficulty) {
            Difficulty.SCOUTED -> 0
            Difficulty.STANDARD -> 1
            Difficulty.RISKY -> 2
        }
        val level = (1 + commanderLevel / 10 + difficultyLevel).coerceIn(1, 5)
        val units = codes.groupingBy { it }.eachCount().entries.sortedBy { it.key }.mapIndexed { index, (code, quantity) ->
            val definition = equipment.require(code)
            val stats = definition.stats.scaled(level)
            UnitBattleSnapshot(
                id = stableUuid("enemy:$seed:$index:$code"),
                code = code,
                level = level,
                cpCost = definition.cpCost * quantity,
                attack = stats.attack,
                armor = stats.armor,
                mobility = stats.mobility,
                recon = stats.recon,
                support = stats.support,
                roles = definition.roles,
                movementProfile = definition.spatial.movementProfile,
                movementPoints = definition.spatial.movementPoints,
                weaponRange = definition.spatial.weaponRange,
                minimumRange = definition.spatial.minimumRange,
                sightRange = definition.spatial.sightRange,
                fireMode = definition.spatial.fireMode,
                quantity = quantity,
            )
        }
        return CombatGroupSnapshot(stableUuid("enemy-group:$seed"), 1, units.sumOf { it.cpCost }, units)
    }

    private fun enemyTactic(archetype: EnemyArchetype): Tactic = when (archetype) {
        EnemyArchetype.ARMOR -> Tactic.ASSAULT
        EnemyArchetype.ARTILLERY, EnemyArchetype.FORTIFIED, EnemyArchetype.AIR_DEFENSE -> Tactic.DEFENSE
        EnemyArchetype.AMBUSH -> Tactic.AMBUSH
        EnemyArchetype.MOBILE, EnemyArchetype.AIR -> Tactic.MANEUVER
    }

    private fun moveAll(
        step: Int,
        units: MutableList<UnitState>,
        controls: Map<String, ObjectiveCaptureState>,
        map: BattleMapDefinition,
        playerPlan: DeploymentPlan,
        enemyPrimaryObjective: String,
        playerTactic: Tactic,
        enemyTactic: Tactic,
        random: Random,
        events: MutableList<SpatialBattleEvent>,
    ) {
        val firstSide = if (random.nextBoolean()) BattleSide.PLAYER else BattleSide.ENEMY
        val ordered = units.filter { it.operational }.sortedWith(
            compareBy<UnitState> { if (it.side == firstSide) 0 else 1 }
                .thenBy { if (tacticFor(it.side, playerTactic, enemyTactic) == Tactic.RECON && "RECON" in it.snapshot.roles) 0 else 1 }
                .thenBy { it.snapshot.id },
        )
        ordered.forEach { unit ->
            if (!unit.operational) return@forEach
            val enemies = units.filter { it.side != unit.side && it.operational }
            if (enemies.isEmpty()) return@forEach
            val allies = units.filter { it.side == unit.side && it.operational }
            val tactic = tacticFor(unit.side, playerTactic, enemyTactic)
            if (shouldHold(step, unit, enemies, allies, controls, map, tactic)) return@forEach

            val primary = if (unit.side == BattleSide.PLAYER) playerPlan.objectiveId else enemyPrimaryObjective
            val objective = chooseObjective(unit, primary, controls, map)
            val goal = if (unit.snapshot.movementProfile == MovementProfile.AIR) {
                enemies.minWithOrNull(compareBy<UnitState> { map.distanceBetween(unit.position, it.position) }.thenBy { it.snapshot.id })?.position
            } else {
                objective?.position
            } ?: return@forEach

            val destination = if (unit.snapshot.fireMode == FireMode.INDIRECT) {
                artilleryDestination(unit, goal, enemies, map, tactic)
            } else {
                destinationAlongPath(unit, goal, enemies, map, tactic)
            }
            if (destination != unit.position) {
                val from = unit.position
                unit.position = destination
                events += SpatialBattleEvent(step, SpatialEventType.UNIT_MOVED, unit.side, unit.snapshot.id, unit.snapshot.code, from, destination)
            }
        }
    }

    private fun shouldHold(
        step: Int,
        unit: UnitState,
        enemies: List<UnitState>,
        allies: List<UnitState>,
        controls: Map<String, ObjectiveCaptureState>,
        map: BattleMapDefinition,
        tactic: Tactic,
    ): Boolean {
        if (availableTargets(unit, enemies, allies, map).isNotEmpty()) return true
        val onObjective = controls.values.firstOrNull { it.definition.position == unit.position }
        val hasStrategicLead = controls.values.count { it.owner == unit.side } >
            controls.values.count { it.owner != null && it.owner != unit.side }
        val threatenedWithoutReply = enemies.any { enemy ->
            unit in availableTargets(enemy, listOf(unit), enemies, map)
        }
        if (
            tactic == Tactic.DEFENSE &&
            onObjective?.owner == unit.side &&
            hasStrategicLead &&
            !threatenedWithoutReply &&
            enemies.any { map.distanceBetween(unit.position, it.position) <= unit.snapshot.sightRange + 2 }
        ) return true
        if (
            tactic == Tactic.AMBUSH &&
            step % AMBUSH_ADVANCE_INTERVAL != 0 &&
            map.terrainAt(unit.position).cover > 0 &&
            enemies.any { map.distanceBetween(unit.position, it.position) <= unit.snapshot.weaponRange + 2 }
        ) return true
        return false
    }

    private fun chooseObjective(
        unit: UnitState,
        primaryId: String,
        controls: Map<String, ObjectiveCaptureState>,
        map: BattleMapDefinition,
    ): StrategicObjective? {
        val primary = controls[primaryId]
        if (primary != null && primary.owner != unit.side) return primary.definition
        return controls.values.filter { it.owner != unit.side }
            .minWithOrNull(compareBy<ObjectiveCaptureState> { map.distanceBetween(unit.position, it.definition.position) }.thenBy { it.definition.id })
            ?.definition
            ?: map.objectives.firstOrNull()
    }

    private fun artilleryDestination(
        unit: UnitState,
        goal: HexCoord,
        enemies: List<UnitState>,
        map: BattleMapDefinition,
        tactic: Tactic,
    ): HexCoord {
        val nearest = enemies.minByOrNull { map.distanceBetween(unit.position, it.position) }
        if (nearest != null && map.distanceBetween(unit.position, nearest.position) < unit.snapshot.minimumRange) {
            return map.neighbors(unit.position)
                .filter { map.terrainAt(it).movementCost(unit.snapshot.movementProfile) != null }
                .maxWithOrNull(compareBy<HexCoord> { map.distanceBetween(it, nearest.position) }.thenByDescending { it.q }.thenByDescending { it.r })
                ?: unit.position
        }
        return destinationAlongPath(unit, goal, enemies, map, tactic)
    }

    private fun destinationAlongPath(
        unit: UnitState,
        goal: HexCoord,
        enemies: List<UnitState>,
        map: BattleMapDefinition,
        tactic: Tactic,
    ): HexCoord {
        val blocked = enemies.filter {
            (it.snapshot.movementProfile == MovementProfile.AIR) == (unit.snapshot.movementProfile == MovementProfile.AIR)
        }
            .map { it.position }.toSet()
        val path = shortestPath(unit.position, goal, unit.snapshot.movementProfile, tactic, unit.snapshot.roles, blocked, map)
        var remaining = unit.snapshot.movementPoints
        var destination = unit.position
        path.drop(1).forEach { next ->
            val cost = movementCost(map.terrainAt(next), unit.snapshot.movementProfile, tactic, unit.snapshot.roles)
            if (cost > remaining) return if (destination == unit.position) next else destination
            remaining -= cost
            destination = next
        }
        return destination
    }

    private fun shortestPath(
        start: HexCoord,
        goal: HexCoord,
        profile: MovementProfile,
        tactic: Tactic,
        roles: Set<String>,
        blocked: Set<HexCoord>,
        map: BattleMapDefinition,
    ): List<HexCoord> {
        val frontier = PriorityQueue(compareBy<PathNode> { it.cost }.thenBy { it.position.q }.thenBy { it.position.r })
        val costs = mutableMapOf(start to 0)
        val previous = mutableMapOf<HexCoord, HexCoord>()
        frontier += PathNode(start, 0)
        var reached: HexCoord? = null
        while (frontier.isNotEmpty()) {
            val current = frontier.remove()
            if (current.cost != costs[current.position]) continue
            if (current.position == goal || (goal in blocked && map.distanceBetween(current.position, goal) == 1)) {
                reached = current.position
                break
            }
            map.neighbors(current.position).forEach { next ->
                if (next in blocked) return@forEach
                val moveCost = map.terrainAt(next).movementCost(profile) ?: return@forEach
                val adjusted = movementCost(map.terrainAt(next), profile, tactic, roles).coerceAtMost(moveCost)
                val nextCost = current.cost + adjusted
                if (nextCost < (costs[next] ?: Int.MAX_VALUE)) {
                    costs[next] = nextCost
                    previous[next] = current.position
                    frontier += PathNode(next, nextCost)
                }
            }
        }
        val end = reached ?: return listOf(start)
        val reversed = mutableListOf(end)
        while (reversed.last() != start) reversed += previous.getValue(reversed.last())
        return reversed.asReversed()
    }

    private fun movementCost(terrain: TerrainType, profile: MovementProfile, tactic: Tactic, roles: Set<String>): Int {
        val base = terrain.movementCost(profile) ?: Int.MAX_VALUE / 4
        val preferred = when (tactic) {
            Tactic.ASSAULT -> false
            Tactic.DEFENSE -> terrain in setOf(TerrainType.HILL, TerrainType.FOREST)
            Tactic.AMBUSH -> terrain.cover > 0
            Tactic.MANEUVER -> terrain in setOf(TerrainType.ROAD, TerrainType.PLAIN, TerrainType.DESERT)
            Tactic.RECON -> "RECON" in roles && terrain !in setOf(TerrainType.MOUNTAIN, TerrainType.WATER)
        }
        return if (preferred) (base - 1).coerceAtLeast(1) else base
    }

    private fun fireAll(
        step: Int,
        units: MutableList<UnitState>,
        map: BattleMapDefinition,
        playerTactic: Tactic,
        enemyTactic: Tactic,
        random: Random,
        events: MutableList<SpatialBattleEvent>,
    ) {
        val firstSide = if (random.nextBoolean()) BattleSide.PLAYER else BattleSide.ENEMY
        val shooters = units.filter { it.operational }.sortedWith(
            compareBy<UnitState> { if (it.side == firstSide) 0 else 1 }.thenBy { it.snapshot.id },
        )
        shooters.forEach { shooter ->
            if (!shooter.operational) return@forEach
            val allies = units.filter { it.side == shooter.side && it.operational }
            val enemies = units.filter { it.side != shooter.side && it.operational }
            val tactic = tacticFor(shooter.side, playerTactic, enemyTactic)
            val target = selectTarget(shooter, availableTargets(shooter, enemies, allies, map), tactic, map) ?: return@forEach
            val hitChance = hitChance(shooter, target, allies, map)
            if (random.nextInt(100) >= hitChance) {
                events += SpatialBattleEvent(
                    step, SpatialEventType.SHOT_MISSED, shooter.side, shooter.snapshot.id, shooter.snapshot.code,
                    from = shooter.position, targetUnitId = target.snapshot.id, targetUnitCode = target.snapshot.code,
                )
                return@forEach
            }
            val damage = damage(shooter, target, map).coerceAtMost(target.hitPoints)
            target.hitPoints = (target.hitPoints - damage).coerceAtLeast(0)
            events += SpatialBattleEvent(
                step, SpatialEventType.UNIT_HIT, shooter.side, shooter.snapshot.id, shooter.snapshot.code,
                from = shooter.position, to = target.position, targetUnitId = target.snapshot.id,
                targetUnitCode = target.snapshot.code, amount = damage,
            )
            if (target.hitPoints == 0) {
                events += SpatialBattleEvent(
                    step, SpatialEventType.UNIT_DESTROYED, shooter.side, shooter.snapshot.id, shooter.snapshot.code,
                    from = shooter.position, to = target.position, targetUnitId = target.snapshot.id, targetUnitCode = target.snapshot.code,
                )
            }
        }
    }

    private fun availableTargets(
        shooter: UnitState,
        enemies: List<UnitState>,
        allies: List<UnitState>,
        map: BattleMapDefinition,
    ): List<UnitState> = enemies.filter { target ->
        val distance = map.distanceBetween(shooter.position, target.position)
        val range = effectiveRange(shooter, target)
        if (distance !in effectiveMinimumRange(shooter, target)..range) return@filter false
        when (shooter.snapshot.fireMode) {
            FireMode.DIRECT -> target.snapshot.movementProfile != MovementProfile.AIR && spottedBy(allies, target, map) && lineOfSight(map, shooter.position, target.position)
            FireMode.INDIRECT -> target.snapshot.movementProfile != MovementProfile.AIR && spottedBy(allies, target, map)
            FireMode.AIR_TO_GROUND -> target.snapshot.movementProfile != MovementProfile.AIR && spottedBy(allies, target, map)
            FireMode.AIR_INTERCEPT -> spottedBy(allies, target, map)
            FireMode.AIR_DEFENSE -> spottedBy(allies, target, map)
        }
    }

    private fun effectiveRange(shooter: UnitState, target: UnitState): Int = when (shooter.snapshot.fireMode) {
        FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE -> if (target.snapshot.movementProfile == MovementProfile.AIR) shooter.snapshot.weaponRange else 1
        else -> shooter.snapshot.weaponRange
    }

    private fun effectiveMinimumRange(shooter: UnitState, target: UnitState): Int =
        if (shooter.snapshot.fireMode in setOf(FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE) && target.snapshot.movementProfile != MovementProfile.AIR) 1
        else shooter.snapshot.minimumRange

    private fun spottedBy(allies: List<UnitState>, target: UnitState, map: BattleMapDefinition): Boolean = allies.any { observer ->
        val distance = map.distanceBetween(observer.position, target.position)
        distance <= observer.snapshot.sightRange &&
            (observer.snapshot.movementProfile == MovementProfile.AIR || target.snapshot.movementProfile == MovementProfile.AIR || lineOfSight(map, observer.position, target.position))
    }

    private fun selectTarget(shooter: UnitState, candidates: List<UnitState>, tactic: Tactic, map: BattleMapDefinition): UnitState? {
        if (candidates.isEmpty()) return null
        val airFirst = if (shooter.snapshot.fireMode in setOf(FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE)) {
            candidates.filter { it.snapshot.movementProfile == MovementProfile.AIR }.ifEmpty { candidates }
        } else candidates
        val comparator = when (tactic) {
            Tactic.ASSAULT -> compareBy<UnitState> { it.hitPoints }.thenBy { map.distanceBetween(shooter.position, it.position) }
            Tactic.DEFENSE -> compareBy<UnitState> { map.distanceBetween(shooter.position, it.position) }.thenByDescending { it.snapshot.attack }
            Tactic.AMBUSH -> compareByDescending<UnitState> { it.snapshot.attack }.thenBy { it.hitPoints }
            Tactic.MANEUVER -> compareBy<UnitState> { if (it.snapshot.fireMode in setOf(FireMode.INDIRECT, FireMode.AIR_DEFENSE)) 0 else 1 }.thenBy { it.hitPoints }
            Tactic.RECON -> compareBy<UnitState> { if ("RECON" in it.snapshot.roles) 0 else 1 }.thenBy { it.hitPoints }
        }
        return airFirst.minWithOrNull(comparator.thenBy { it.snapshot.id })
    }

    private fun hitChance(shooter: UnitState, target: UnitState, allies: List<UnitState>, map: BattleMapDefinition): Int {
        val observerRecon = allies.filter { map.distanceBetween(it.position, target.position) <= it.snapshot.sightRange }.maxOfOrNull { it.snapshot.recon } ?: 0
        val base = when (shooter.snapshot.fireMode) {
            FireMode.INDIRECT -> 48 + observerRecon / 5
            FireMode.AIR_TO_GROUND -> 55 + observerRecon / 6
            else -> 60 + shooter.snapshot.recon / 4
        }
        val elevation = (map.elevationAt(shooter.position) - map.elevationAt(target.position)) * 3
        val cover = map.terrainAt(target.position).cover * if (shooter.snapshot.fireMode == FireMode.INDIRECT) 4 else 8
        return (base + elevation - target.snapshot.mobility / 6 - cover).coerceIn(15, 92)
    }

    private fun damage(shooter: UnitState, target: UnitState, map: BattleMapDefinition): Int {
        var attack = shooter.snapshot.attack
        if (shooter.snapshot.fireMode in setOf(FireMode.AIR_INTERCEPT, FireMode.AIR_DEFENSE) && target.snapshot.movementProfile != MovementProfile.AIR) {
            attack = attack * 35 / 100
        }
        val raw = (attack * 8 / 5 - target.snapshot.armor * 3 / 5).coerceIn(6, 58)
        val singleUnitDamage = (raw * (100 - map.terrainAt(target.position).cover * 10) / 100).coerceAtLeast(4)
        return singleUnitDamage * shooter.remainingQuantity
    }

    private fun updateObjectives(
        step: Int,
        units: List<UnitState>,
        controls: Map<String, ObjectiveCaptureState>,
        events: MutableList<SpatialBattleEvent>,
    ) {
        controls.values.forEach { control ->
            val occupiers = units.filter {
                it.operational && it.snapshot.movementProfile != MovementProfile.AIR && it.position == control.definition.position
            }.map { it.side }.toSet()
            val update = control.advance(occupiers) ?: return@forEach
            events += SpatialBattleEvent(
                step, SpatialEventType.CAPTURE_PROGRESS, update.side, objectiveId = control.definition.id,
                amount = update.progress,
            )
            if (update.captured) {
                events += SpatialBattleEvent(step, SpatialEventType.OBJECTIVE_CAPTURED, update.side, objectiveId = control.definition.id)
            }
        }
    }

    private fun decideOperationalWinner(units: List<UnitState>, controls: Map<String, ObjectiveCaptureState>): BattleSide {
        fun score(side: BattleSide): Int = controls.values.count { it.owner == side } * 1_000 +
            units.filter { it.side == side && it.operational }.sumOf { it.hitPoints }
        return if (score(BattleSide.PLAYER) >= score(BattleSide.ENEMY)) BattleSide.PLAYER else BattleSide.ENEMY
    }

    private fun lineOfSight(map: BattleMapDefinition, from: HexCoord, to: HexCoord): Boolean =
        map.lineBetween(from, to).drop(1).dropLast(1).none { map.terrainAt(it).blocksLineOfSight }

    private fun tacticFor(side: BattleSide, player: Tactic, enemy: Tactic): Tactic = if (side == BattleSide.PLAYER) player else enemy

    private fun unitResults(units: List<UnitState>, side: BattleSide): List<SpatialUnitResult> = units.filter { it.side == side }.map {
        SpatialUnitResult(
            it.snapshot.id,
            it.snapshot.code,
            it.side,
            it.position,
            it.hitPoints,
            it.routed,
            it.snapshot.quantity,
            if (it.hitPoints <= 0) 0 else (it.hitPoints + 99) / 100,
        )
    }

    private fun stableUuid(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private data class PathNode(val position: HexCoord, val cost: Int)

    private data class UnitState(
        val snapshot: UnitBattleSnapshot,
        val side: BattleSide,
        var position: HexCoord,
        var hitPoints: Int,
        var routed: Boolean,
    ) {
        val operational: Boolean get() = hitPoints > 0 && !routed
        val remainingQuantity: Int get() = if (operational) (hitPoints + 99) / 100 else 0
    }

    companion object {
        const val MAX_STEPS = 48
        private const val AMBUSH_ADVANCE_INTERVAL = 3
        private const val SPATIAL_SEED_MASK = 0x5A17C0DE4B9L
    }
}
