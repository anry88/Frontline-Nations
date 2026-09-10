package com.tggames.frontline.battle

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

enum class Tactic(val code: String, val title: String, val icon: String, val hint: String) {
    ASSAULT("assault", "Штурм", "⚔️", "прорывает артиллерийские позиции"),
    DEFENSE("defense", "Оборона", "🛡️", "сдерживает бронетанковый натиск"),
    AMBUSH("ambush", "Засада", "🌲", "опасна для брони в сложной местности"),
    MANEUVER("maneuver", "Манёвр", "↗️", "обходит оборону и дальний огонь"),
    RECON("recon", "Разведка боем", "🔭", "вскрывает засады и захватывает инициативу"),
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
}

enum class Difficulty(
    val title: String,
    val icon: String,
    val rewardPercent: Int,
    val enemyBonus: Int,
    val intelLevel: String,
) {
    SCOUTED("Разведанная", "🔎", 90, -2, "точная"),
    STANDARD("Обычная", "⚖️", 100, 3, "частичная"),
    RISKY("Рискованная", "🔥", 140, 12, "ограниченная"),
}

data class Battlefield(val location: String, val biome: String)

data class OperationOffer(
    val slot: Int,
    val battlefield: Battlefield,
    val enemy: EnemyArchetype,
    val difficulty: Difficulty,
) {
    val title: String get() = "${difficulty.icon} ${battlefield.location}"

    fun intel(): String = when (difficulty) {
        Difficulty.SCOUTED -> enemy.intel
        Difficulty.STANDARD -> "вероятно: ${enemy.title}"
        Difficulty.RISKY -> "состав противника неизвестен"
    }
}

data class BattleEvent(
    val round: Int,
    val phase: String,
    val text: String,
    val playerScore: Int,
    val enemyScore: Int,
)

data class BattleResult(
    val victory: Boolean,
    val playerPower: Int,
    val enemyPower: Int,
    val tacticBonus: Int,
    val terrainBonus: Int,
    val xp: Int,
    val credits: Int,
    val researchPoints: Int,
    val materials: Int,
    val seed: Long,
    val seedHash: String,
    val events: List<BattleEvent>,
)

@Component
class BattleEngine {
    private val battlefields = listOf(
        Battlefield("Карпатский перевал", "горы"),
        Battlefield("Дунайская долина", "речная долина"),
        Battlefield("Побережье Адриатики", "побережье"),
        Battlefield("Патагонийское плато", "холмистая местность"),
        Battlefield("Сахарский коридор", "пустыня"),
        Battlefield("Алтайский рубеж", "степь"),
        Battlefield("Полесский рубеж", "лес"),
        Battlefield("Северная тундра", "тундра"),
    )

    fun offers(serverSalt: String, offerKey: String): List<OperationOffer> {
        val random = seededRandom(serverSalt, "offers:$offerKey").random
        val locations = battlefields.shuffled(random).take(3)
        val enemies = EnemyArchetype.entries.shuffled(random).take(3)
        val difficulties = Difficulty.entries.shuffled(random)
        return (0..2).map { slot ->
            OperationOffer(slot, locations[slot], enemies[slot], difficulties[slot])
        }
    }

    fun resolve(
        serverSalt: String,
        battleKey: String,
        commanderLevel: Int,
        operation: OperationOffer,
        tactic: Tactic,
    ): BattleResult = replay(
        seed = seededRandom(serverSalt, "battle:$battleKey").seed,
        commanderLevel = commanderLevel,
        operation = operation,
        tactic = tactic,
    )

    fun replay(
        seed: Long,
        commanderLevel: Int,
        operation: OperationOffer,
        tactic: Tactic,
    ): BattleResult {
        val random = Random(seed)
        val tacticBonus = tacticBonus(tactic, operation.enemy)
        val terrainBonus = terrainBonus(tactic, operation.battlefield.biome)
        val initiative = random.nextInt(-5, 6) + initiativeBonus(tactic)
        val playerPower = 62 + commanderLevel * 4 + random.nextInt(0, 21) + tacticBonus + terrainBonus + initiative
        val enemyPower = 64 + commanderLevel * 3 + operation.difficulty.enemyBonus + random.nextInt(0, 21)
        val simulatedEvents = simulateRounds(random, tactic, operation, playerPower, enemyPower, initiative)
        val playerScore = simulatedEvents.sumOf { it.playerScore }
        val enemyScore = simulatedEvents.sumOf { it.enemyScore }
        val victory = playerScore >= enemyScore
        val events = simulatedEvents.dropLast(1) + simulatedEvents.last().copy(
            text = if (victory) "Цель операции взята под контроль" else "Группа организованно отошла с рубежа",
        )
        val multiplier = operation.difficulty.rewardPercent
        val xp = reward(if (victory) 150 + random.nextInt(0, 51) else 70 + random.nextInt(0, 31), multiplier)
        val credits = reward(if (victory) 165 + random.nextInt(0, 61) else 75 + random.nextInt(0, 31), multiplier)
        val researchPoints = reward(if (victory) 10 + random.nextInt(0, 6) else 4 + random.nextInt(0, 4), multiplier)
        val materials = reward(if (victory) 9 + random.nextInt(0, 7) else 3 + random.nextInt(0, 4), multiplier)

        return BattleResult(
            victory = victory,
            playerPower = playerPower,
            enemyPower = enemyPower,
            tacticBonus = tacticBonus,
            terrainBonus = terrainBonus,
            xp = xp,
            credits = credits,
            researchPoints = researchPoints,
            materials = materials,
            seed = seed,
            seedHash = MessageDigest.getInstance("SHA-256")
                .digest(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array())
                .toHex(),
            events = events,
        )
    }

    private fun simulateRounds(
        random: Random,
        tactic: Tactic,
        operation: OperationOffer,
        playerPower: Int,
        enemyPower: Int,
        initiative: Int,
    ): List<BattleEvent> {
        val rounds = 8 + random.nextInt(0, 5)
        var playerCondition = 100
        var enemyCondition = 100
        return (1..rounds).map { round ->
            val phase = when {
                round <= 2 -> "Разведка"
                round <= rounds - 2 -> "Контакт"
                else -> "Финал"
            }
            val playerRoll = playerPower + random.nextInt(-12, 13) + if (round == 1) initiative else 0
            val enemyRoll = enemyPower + random.nextInt(-12, 13)
            val playerScore = (playerRoll * playerCondition / 100).coerceAtLeast(1)
            val enemyScore = (enemyRoll * enemyCondition / 100).coerceAtLeast(1)
            if (playerScore >= enemyScore) enemyCondition = (enemyCondition - random.nextInt(3, 9)).coerceAtLeast(45)
            else playerCondition = (playerCondition - random.nextInt(3, 9)).coerceAtLeast(45)
            BattleEvent(
                round = round,
                phase = phase,
                text = eventText(round, rounds, playerScore >= enemyScore, tactic, operation),
                playerScore = playerScore,
                enemyScore = enemyScore,
            )
        }
    }

    private fun eventText(
        round: Int,
        rounds: Int,
        playerWonRound: Boolean,
        tactic: Tactic,
        operation: OperationOffer,
    ): String = when {
        round == 1 && playerWonRound -> "${tactic.title}: группа захватила инициативу"
        round == 1 -> "${operation.enemy.title.replaceFirstChar { it.uppercase() }} перехватила инициативу"
        round == rounds -> "Стороны ведут бой за главную цель"
        playerWonRound -> listOf(
            "точный огонь подавил ключевую позицию",
            "фланговый участок противника потерял темп",
            "разведданные позволили сорвать атаку",
        )[round % 3]
        else -> listOf(
            "противник удержал огневой рубеж",
            "группа попала под ответный огонь",
            "продвижение замедлено сопротивлением",
        )[round % 3]
    }

    private fun tacticBonus(tactic: Tactic, enemy: EnemyArchetype): Int = when (tactic) {
        Tactic.ASSAULT -> when (enemy) {
            EnemyArchetype.ARTILLERY -> 13
            EnemyArchetype.FORTIFIED -> -5
            else -> 3
        }
        Tactic.DEFENSE -> when (enemy) {
            EnemyArchetype.ARMOR, EnemyArchetype.MOBILE -> 12
            EnemyArchetype.ARTILLERY -> -5
            else -> 3
        }
        Tactic.AMBUSH -> when (enemy) {
            EnemyArchetype.ARMOR, EnemyArchetype.MOBILE -> 13
            EnemyArchetype.AMBUSH -> -4
            else -> 2
        }
        Tactic.MANEUVER -> when (enemy) {
            EnemyArchetype.ARTILLERY, EnemyArchetype.FORTIFIED -> 12
            EnemyArchetype.MOBILE -> -4
            else -> 3
        }
        Tactic.RECON -> when (enemy) {
            EnemyArchetype.AMBUSH -> 14
            EnemyArchetype.ARMOR -> -3
            else -> 4
        }
    }

    private fun terrainBonus(tactic: Tactic, biome: String): Int = when {
        tactic == Tactic.AMBUSH && biome in setOf("горы", "лес", "холмистая местность") -> 7
        tactic == Tactic.MANEUVER && biome in setOf("степь", "пустыня", "речная долина") -> 6
        tactic == Tactic.RECON && biome in setOf("пустыня", "тундра", "побережье") -> 5
        tactic == Tactic.ASSAULT && biome == "горы" -> -4
        tactic == Tactic.DEFENSE && biome == "речная долина" -> 4
        else -> 0
    }

    private fun initiativeBonus(tactic: Tactic): Int = when (tactic) {
        Tactic.RECON -> 5
        Tactic.MANEUVER -> 3
        Tactic.AMBUSH -> 2
        Tactic.ASSAULT -> 1
        Tactic.DEFENSE -> 0
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
}

private data class SeededRandom(val seed: Long, val random: Random)
