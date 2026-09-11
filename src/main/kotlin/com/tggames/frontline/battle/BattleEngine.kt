package com.tggames.frontline.battle

import com.tggames.frontline.catalog.FireMode
import com.tggames.frontline.catalog.MovementProfile
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

enum class Tactic(val code: String, val title: String, val icon: String, val hint: String) {
    ASSAULT("assault", "Штурм", "⚔️", "прямое продвижение и добивание повреждённых целей"),
    DEFENSE("defense", "Оборона", "🛡️", "удержание объектов и огонь по ближайшей угрозе"),
    AMBUSH("ambush", "Засада", "🌲", "укрытые маршруты и приоритет опасных целей"),
    MANEUVER("maneuver", "Манёвр", "↗️", "быстрые маршруты и охота за артиллерией и ПВО"),
    RECON("recon", "Разведка боем", "🔭", "разведка идёт первой и подавляет вражеских разведчиков"),
    ;

    companion object {
        fun fromCode(code: String): Tactic? = entries.firstOrNull { it.code == code }
    }
}

enum class EnemyArchetype(val title: String, val intel: String) {
    ARMOR("бронетанковая группа", "обнаружено много тяжёлой брони"),
    ARTILLERY("артиллерийская группа", "замечены дальнобойные позиции"),
    FORTIFIED("укреплённая группа", "противник готовит эшелонированную оборону"),
    AMBUSH("скрытная группа", "зафиксированы маскировка и ложные позиции"),
    MOBILE("мобильная группа", "высокая активность лёгких машин на флангах"),
    AIR("авиационная группа", "зафиксирована ударная авиация"),
    AIR_DEFENSE("группа ПВО", "обнаружена плотная противовоздушная оборона"),
}

enum class Difficulty(val title: String, val icon: String, val rewardPercent: Int, val enemyBonus: Int, val intelLevel: String) {
    SCOUTED("Разведанная", "🔎", 90, -2, "точная"),
    STANDARD("Обычная", "⚖️", 100, 3, "частичная"),
    RISKY("Рискованная", "🔥", 140, 12, "ограниченная"),
}

data class Battlefield(val location: String, val biome: String)

data class OperationOffer(val slot: Int, val battlefield: Battlefield, val enemy: EnemyArchetype, val difficulty: Difficulty) {
    val title: String get() = "${difficulty.icon} ${battlefield.location}"
}

data class UnitBattleSnapshot(
    val id: UUID,
    val code: String,
    val level: Int,
    val cpCost: Int,
    val attack: Int,
    val armor: Int,
    val mobility: Int,
    val recon: Int,
    val support: Int,
    val roles: Set<String>,
    val movementProfile: MovementProfile = MovementProfile.TRACKED,
    val movementPoints: Int = 4,
    val weaponRange: Int = 2,
    val minimumRange: Int = 1,
    val sightRange: Int = 3,
    val fireMode: FireMode = FireMode.DIRECT,
)

data class CombatGroupSnapshot(val id: UUID, val version: Int, val cpLimit: Int, val units: List<UnitBattleSnapshot>) {
    val usedCp: Int get() = units.sumOf { it.cpCost }
}

data class TacticAssessment(val fit: Int, val bonus: Int, val requirementsMet: Boolean, val missingRoles: Set<String>)

data class BattleEvent(val round: Int, val phase: String, val text: String, val playerScore: Int, val enemyScore: Int)

data class BattleResult(
    val victory: Boolean,
    val playerPower: Int,
    val enemyPower: Int,
    val compositionPower: Int,
    val tacticFit: Int,
    val tacticBonus: Int,
    val counterBonus: Int,
    val terrainBonus: Int,
    val xp: Int,
    val credits: Int,
    val researchPoints: Int,
    val materials: Int,
    val seed: Long,
    val seedHash: String,
    val events: List<BattleEvent>,
    val spatial: SpatialBattleResult? = null,
)

@Component
class BattleEngine(
    private val spatialEngine: SpatialBattleEngine,
    private val mapCatalog: BattleMapCatalog,
) {
    val battlefields = listOf(
        Battlefield("Карпатский перевал", "горы"), Battlefield("Дунайская долина", "речная долина"),
        Battlefield("Побережье Адриатики", "побережье"), Battlefield("Патагонийское плато", "холмистая местность"),
        Battlefield("Сахарский коридор", "пустыня"), Battlefield("Алтайский рубеж", "степь"),
        Battlefield("Полесский рубеж", "лес"), Battlefield("Северная тундра", "тундра"),
        Battlefield("Гобийская котловина", "пустыня"), Battlefield("Дельта Нила", "речная долина"),
        Battlefield("Анатолийское плато", "холмистая местность"), Battlefield("Рейнская равнина", "равнина"),
        Battlefield("Предгорья Атласа", "горы"), Battlefield("Амазонская низменность", "джунгли"),
        Battlefield("Великая рифтовая долина", "степь"), Battlefield("Дельта Меконга", "болота"),
        Battlefield("Деканское плато", "холмистая местность"), Battlefield("Андский перевал", "горы"),
        Battlefield("Аравийское побережье", "побережье"), Battlefield("Великие равнины", "равнина"),
        Battlefield("Кавказский хребет", "горы"), Battlefield("Балтийские болота", "болота"),
        Battlefield("Австралийский аутбэк", "пустыня"), Battlefield("Камчатское побережье", "тундра"),
    )

    fun offers(serverSalt: String, offerKey: String): List<OperationOffer> {
        val random = seededRandom(serverSalt, "offers:$offerKey").random
        val locations = battlefields.shuffled(random).take(OFFER_COUNT)
        val enemies = EnemyArchetype.entries.shuffled(random).take(OFFER_COUNT)
        val difficulties = List(OFFER_COUNT) { Difficulty.entries[it % Difficulty.entries.size] }.shuffled(random)
        return (0 until OFFER_COUNT).map { OperationOffer(it, locations[it], enemies[it], difficulties[it]) }
    }

    fun mapFor(operation: OperationOffer): BattleMapDefinition = mapCatalog.forBiome(operation.battlefield.biome)

    fun resolve(
        serverSalt: String,
        battleKey: String,
        commanderLevel: Int,
        operation: OperationOffer,
        tactic: Tactic,
        group: CombatGroupSnapshot,
        plan: DeploymentPlan,
    ): BattleResult = replay(seededRandom(serverSalt, "battle:$battleKey").seed, commanderLevel, operation, tactic, group, plan)

    fun resolve(serverSalt: String, battleKey: String, commanderLevel: Int, operation: OperationOffer, tactic: Tactic, group: CombatGroupSnapshot): BattleResult {
        val map = mapFor(operation)
        return resolve(serverSalt, battleKey, commanderLevel, operation, tactic, group, DeploymentPlan(map.playerEntries.first().id, map.objectives.first().id))
    }

    fun replay(
        seed: Long,
        commanderLevel: Int,
        operation: OperationOffer,
        tactic: Tactic,
        group: CombatGroupSnapshot,
        plan: DeploymentPlan,
    ): BattleResult {
        val spatial = spatialEngine.simulate(seed, commanderLevel, operation, tactic, group, plan, mapFor(operation))
        val random = Random(seed xor 0x31A77E41L)
        val playerPower = compositionPower(group)
        val enemyPower = compositionPower(spatial.enemyGroup)
        val victory = spatial.winner == BattleSide.PLAYER
        val multiplier = operation.difficulty.rewardPercent
        return BattleResult(
            victory = victory,
            playerPower = playerPower,
            enemyPower = enemyPower,
            compositionPower = playerPower,
            tacticFit = 0,
            tacticBonus = 0,
            counterBonus = 0,
            terrainBonus = 0,
            xp = reward(if (victory) 150 + random.nextInt(0, 51) else 70 + random.nextInt(0, 31), multiplier),
            credits = reward(if (victory) 165 + random.nextInt(0, 61) else 75 + random.nextInt(0, 31), multiplier),
            researchPoints = reward(if (victory) 10 + random.nextInt(0, 6) else 4 + random.nextInt(0, 4), multiplier),
            materials = reward(if (victory) 9 + random.nextInt(0, 7) else 3 + random.nextInt(0, 4), multiplier),
            seed = seed,
            seedHash = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array()).toHex(),
            events = summarizeSpatialEvents(spatial),
            spatial = spatial,
        )
    }

    fun replay(seed: Long, commanderLevel: Int, operation: OperationOffer, tactic: Tactic, group: CombatGroupSnapshot): BattleResult {
        val map = mapFor(operation)
        return replay(seed, commanderLevel, operation, tactic, group, DeploymentPlan(map.playerEntries.first().id, map.objectives.first().id))
    }

    fun replayV3(seed: Long, commanderLevel: Int, operation: OperationOffer, tactic: Tactic, group: CombatGroupSnapshot): BattleResult {
        require(group.units.isNotEmpty()) { "Combat group cannot be empty" }
        val random = Random(seed)
        val assessment = assess(tactic, group)
        val counterBonus = counterBonus(tactic, operation.enemy, group)
        val terrainBonus = terrainBonus(tactic, operation.battlefield.biome, group)
        val compositionPower = compositionPower(group)
        val initiative = random.nextInt(-5, 6) + initiativeBonus(tactic, group)
        val playerPower = compositionPower + commanderLevel * 2 + random.nextInt(0, 17) + assessment.bonus + counterBonus + terrainBonus + initiative
        val enemyPower = 58 + commanderLevel * 2 + operation.difficulty.enemyBonus + random.nextInt(0, 19)
        val simulatedEvents = simulateRounds(random, tactic, operation, playerPower, enemyPower, initiative)
        val victory = simulatedEvents.sumOf { it.playerScore } >= simulatedEvents.sumOf { it.enemyScore }
        val events = simulatedEvents.dropLast(1) + simulatedEvents.last().copy(
            text = if (victory) "Цель операции взята под контроль" else "Группа организованно отошла с рубежа",
        )
        val multiplier = operation.difficulty.rewardPercent
        return BattleResult(
            victory, playerPower, enemyPower, compositionPower, assessment.fit, assessment.bonus, counterBonus, terrainBonus,
            reward(if (victory) 150 + random.nextInt(0, 51) else 70 + random.nextInt(0, 31), multiplier),
            reward(if (victory) 165 + random.nextInt(0, 61) else 75 + random.nextInt(0, 31), multiplier),
            reward(if (victory) 10 + random.nextInt(0, 6) else 4 + random.nextInt(0, 4), multiplier),
            reward(if (victory) 9 + random.nextInt(0, 7) else 3 + random.nextInt(0, 4), multiplier),
            seed,
            MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array()).toHex(),
            events,
        )
    }

    fun compositionPower(group: CombatGroupSnapshot): Int {
        val attack = group.units.sumOf { it.attack }
        val armor = group.units.sumOf { it.armor }
        val mobility = group.units.sumOf { it.mobility }
        val recon = group.units.sumOf { it.recon }
        val support = group.units.sumOf { it.support }
        return ((attack * 3 + armor * 2 + mobility + recon + support) / 10 + group.units.size * 2).coerceAtLeast(1)
    }

    /** Legacy engine-v3 assessment retained only to reproduce historical battles. */
    fun assess(tactic: Tactic, group: CombatGroupSnapshot): TacticAssessment {
        require(group.units.isNotEmpty()) { "Combat group cannot be empty" }
        val required = when (tactic) {
            Tactic.ASSAULT -> setOf("ARMOR", "FIREPOWER")
            Tactic.DEFENSE -> setOf("ARMOR", "SUPPORT")
            Tactic.AMBUSH -> setOf("RECON", "FIREPOWER")
            Tactic.MANEUVER -> setOf("MOBILE", "ARMOR")
            Tactic.RECON -> setOf("RECON")
        }
        val missing = required - group.units.flatMap { it.roles }.toSet()
        val weighted = group.units.sumOf { unit ->
            when (tactic) {
                Tactic.ASSAULT -> unit.attack * 45 + unit.armor * 35 + unit.mobility * 20
                Tactic.DEFENSE -> unit.armor * 45 + unit.support * 35 + unit.attack * 20
                Tactic.AMBUSH -> unit.recon * 40 + unit.attack * 35 + unit.mobility * 25
                Tactic.MANEUVER -> unit.mobility * 45 + unit.recon * 25 + unit.attack * 30
                Tactic.RECON -> unit.recon * 55 + unit.mobility * 30 + unit.support * 15
            }
        } / 100
        val fit = (weighted * 100 / (group.units.size * 36) + if (missing.isEmpty()) 10 else -15).coerceIn(0, 100)
        return TacticAssessment(fit, ((fit - 50) / 2).coerceIn(-12, 18), missing.isEmpty(), missing)
    }

    private fun counterBonus(tactic: Tactic, enemy: EnemyArchetype, group: CombatGroupSnapshot): Int {
        val roles = group.units.flatMap { it.roles }.toSet()
        val equipment = when (enemy) {
            EnemyArchetype.ARMOR -> if ("FIREPOWER" in roles || "AIR" in roles) 5 else -3
            EnemyArchetype.ARTILLERY -> if ("MOBILE" in roles && "RECON" in roles) 6 else -3
            EnemyArchetype.FORTIFIED -> if ("FIREPOWER" in roles) 5 else -4
            EnemyArchetype.AMBUSH -> if ("RECON" in roles) 7 else -5
            EnemyArchetype.MOBILE -> if ("MOBILE" in roles || "AIR" in roles) 4 else -3
            EnemyArchetype.AIR -> if ("AIR_DEFENSE" in roles) 9 else -7
            EnemyArchetype.AIR_DEFENSE -> if ("ARMOR" in roles || "FIREPOWER" in roles) 5 else -4
        }
        val order = when (tactic) {
            Tactic.ASSAULT -> if (enemy == EnemyArchetype.ARTILLERY) 5 else if (enemy == EnemyArchetype.FORTIFIED) -4 else 1
            Tactic.DEFENSE -> if (enemy in setOf(EnemyArchetype.ARMOR, EnemyArchetype.MOBILE, EnemyArchetype.AIR)) 5 else if (enemy == EnemyArchetype.ARTILLERY) -4 else 1
            Tactic.AMBUSH -> if (enemy in setOf(EnemyArchetype.ARMOR, EnemyArchetype.MOBILE)) 5 else if (enemy == EnemyArchetype.AMBUSH) -3 else 1
            Tactic.MANEUVER -> if (enemy in setOf(EnemyArchetype.ARTILLERY, EnemyArchetype.FORTIFIED, EnemyArchetype.AIR_DEFENSE)) 5 else if (enemy == EnemyArchetype.MOBILE) -3 else 1
            Tactic.RECON -> if (enemy == EnemyArchetype.AMBUSH) 6 else if (enemy == EnemyArchetype.ARMOR) -2 else 2
        }
        return equipment + order
    }

    private fun terrainBonus(tactic: Tactic, biome: String, group: CombatGroupSnapshot): Int {
        val roles = group.units.flatMap { it.roles }
        val heavy = group.units.count { it.code in setOf("MBT", "ARTILLERY") }
        val mobile = roles.count { it == "MOBILE" }
        val recon = roles.count { it == "RECON" }
        val air = roles.count { it == "AIR" }
        val composition = when (biome) {
            "горы" -> recon * 2 - heavy * 2
            "лес", "джунгли" -> recon * 2 + mobile - air * 2
            "пустыня", "степь", "равнина" -> mobile * 2 + air
            "побережье" -> air * 2 + roles.count { it == "AIR_DEFENSE" }
            "болота", "речная долина" -> recon - heavy
            "тундра" -> recon + roles.count { it == "SUPPORT" } - air
            else -> 0
        }
        val order = when {
            tactic == Tactic.AMBUSH && biome in setOf("горы", "лес", "джунгли", "холмистая местность") -> 4
            tactic == Tactic.MANEUVER && biome in setOf("степь", "пустыня", "равнина") -> 4
            tactic == Tactic.RECON && biome in setOf("пустыня", "тундра", "побережье") -> 3
            tactic == Tactic.ASSAULT && biome == "горы" -> -3
            tactic == Tactic.DEFENSE && biome in setOf("речная долина", "болота") -> 3
            else -> 0
        }
        return (composition + order).coerceIn(-10, 12)
    }

    private fun initiativeBonus(tactic: Tactic, group: CombatGroupSnapshot): Int {
        val equipment = (group.units.sumOf { it.recon } + group.units.sumOf { it.mobility }) / (group.units.size * 20)
        return equipment + when (tactic) { Tactic.RECON -> 4; Tactic.MANEUVER -> 3; Tactic.AMBUSH -> 2; Tactic.ASSAULT -> 1; Tactic.DEFENSE -> 0 }
    }

    private fun simulateRounds(random: Random, tactic: Tactic, operation: OperationOffer, playerPower: Int, enemyPower: Int, initiative: Int): List<BattleEvent> {
        val rounds = 8 + random.nextInt(0, 5)
        var playerCondition = 100
        var enemyCondition = 100
        return (1..rounds).map { round ->
            val phase = when { round <= 2 -> "Разведка"; round <= rounds - 2 -> "Контакт"; else -> "Финал" }
            val playerRoll = playerPower + random.nextInt(-12, 13) + if (round == 1) initiative else 0
            val enemyRoll = enemyPower + random.nextInt(-12, 13)
            val playerScore = (playerRoll * playerCondition / 100).coerceAtLeast(1)
            val enemyScore = (enemyRoll * enemyCondition / 100).coerceAtLeast(1)
            if (playerScore >= enemyScore) enemyCondition = (enemyCondition - random.nextInt(3, 9)).coerceAtLeast(45)
            else playerCondition = (playerCondition - random.nextInt(3, 9)).coerceAtLeast(45)
            BattleEvent(round, phase, eventText(round, rounds, playerScore >= enemyScore, tactic, operation), playerScore, enemyScore)
        }
    }

    private fun eventText(round: Int, rounds: Int, won: Boolean, tactic: Tactic, operation: OperationOffer): String = when {
        round == 1 && won -> "${tactic.title}: группа захватила инициативу"
        round == 1 -> "${operation.enemy.title.replaceFirstChar { it.uppercase() }} перехватила инициативу"
        round == rounds -> "Стороны ведут бой за главную цель"
        won -> listOf("точный огонь подавил ключевую позицию", "фланговый участок противника потерял темп", "разведданные позволили сорвать атаку")[round % 3]
        else -> listOf("противник удержал огневой рубеж", "группа попала под ответный огонь", "продвижение замедлено сопротивлением")[round % 3]
    }

    private fun summarizeSpatialEvents(result: SpatialBattleResult): List<BattleEvent> {
        var playerCondition = result.playerUnits.size * 100
        var enemyCondition = result.enemyUnits.size * 100
        return (1..result.steps).map { step ->
            val stepEvents = result.events.filter { it.step == step }
            stepEvents.filter { it.type == SpatialEventType.UNIT_HIT }.forEach {
                if (it.side == BattleSide.PLAYER) enemyCondition -= it.amount ?: 0 else playerCondition -= it.amount ?: 0
            }
            val captured = stepEvents.firstOrNull { it.type == SpatialEventType.OBJECTIVE_CAPTURED }?.objectiveId
            val destroyed = stepEvents.count { it.type == SpatialEventType.UNIT_DESTROYED }
            val phase = when {
                step <= 2 -> "Развёртывание"
                captured != null -> "Захват"
                else -> "Контакт"
            }
            val text = when {
                captured != null -> "Объект $captured перешёл под контроль"
                destroyed > 0 -> "Потеряно боевых единиц: $destroyed"
                step == result.steps -> "Бой завершён: ${result.endReason.name}"
                else -> "Группы меняют позиции и ведут огонь"
            }
            BattleEvent(
                round = step,
                phase = phase,
                text = text,
                playerScore = playerCondition.coerceAtLeast(0),
                enemyScore = enemyCondition.coerceAtLeast(0),
            )
        }
    }

    private fun reward(base: Int, percent: Int): Int = base * percent / 100

    private fun seededRandom(serverSalt: String, key: String): SeededRandom {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(key.toByteArray(StandardCharsets.UTF_8))
        val seed = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long
        return SeededRandom(seed, Random(seed))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object { const val OFFER_COUNT = 5 }
}

private data class SeededRandom(val seed: Long, val random: Random)
