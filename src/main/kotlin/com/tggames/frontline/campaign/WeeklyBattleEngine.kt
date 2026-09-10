package com.tggames.frontline.campaign

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

data class AllianceForce(
    val code: String,
    val contributedPower: Long,
    val contributors: Int,
)

data class WeeklyBalance(
    val npcBasePower: Int,
    val npcPerMissingContributor: Int,
    val maxNpcCompensation: Int,
    val contributionSoftCap: Int,
    val overflowDivisor: Int,
)

data class WeeklyBattleEvent(
    val phase: String,
    val text: String,
    val scoreA: Long,
    val scoreB: Long,
)

data class WeeklyBattleResult(
    val allianceA: String,
    val allianceB: String,
    val effectivePowerA: Long,
    val effectivePowerB: Long,
    val npcBonusA: Int,
    val npcBonusB: Int,
    val scoreA: Long,
    val scoreB: Long,
    val winnerCode: String,
    val seed: Long,
    val seedHash: String,
    val events: List<WeeklyBattleEvent>,
)

@Component
class WeeklyBattleEngine {
    fun resolve(
        serverSalt: String,
        weekKey: String,
        pairIndex: Int,
        battlefield: String,
        forceA: AllianceForce,
        forceB: AllianceForce,
        balance: WeeklyBalance,
    ): WeeklyBattleResult {
        require(forceA.code != forceB.code)
        require(balance.overflowDivisor > 0)
        val seed = deriveSeed(serverSalt, "$weekKey:$pairIndex:$battlefield:${forceA.code}:${forceB.code}")
        val random = Random(seed)
        val npcA = npcCompensation(forceA, forceB, balance)
        val npcB = npcCompensation(forceB, forceA, balance)
        val effectiveA = balance.npcBasePower.toLong() + cappedContribution(forceA.contributedPower, balance) + npcA
        val effectiveB = balance.npcBasePower.toLong() + cappedContribution(forceB.contributedPower, balance) + npcB
        val phases = listOf("Разведка", "Подготовка", "Основной контакт", "Прорыв")
        var scoreA = 0L
        var scoreB = 0L
        val events = phases.mapIndexed { index, phase ->
            val phaseA = effectiveA * random.nextInt(88, 113) / 100
            val phaseB = effectiveB * random.nextInt(88, 113) / 100
            scoreA += phaseA
            scoreB += phaseB
            WeeklyBattleEvent(
                phase = phase,
                text = phaseText(index, phaseA, phaseB, forceA.code, forceB.code),
                scoreA = scoreA,
                scoreB = scoreB,
            )
        }
        if (scoreA == scoreB) {
            if (random.nextBoolean()) scoreA++ else scoreB++
        }
        val winner = if (scoreA > scoreB) forceA.code else forceB.code
        return WeeklyBattleResult(
            allianceA = forceA.code,
            allianceB = forceB.code,
            effectivePowerA = effectiveA,
            effectivePowerB = effectiveB,
            npcBonusA = npcA,
            npcBonusB = npcB,
            scoreA = scoreA,
            scoreB = scoreB,
            winnerCode = winner,
            seed = seed,
            seedHash = MessageDigest.getInstance("SHA-256")
                .digest(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(seed).array())
                .toHex(),
            events = events,
        )
    }

    internal fun cappedContribution(power: Long, balance: WeeklyBalance): Long {
        val cap = balance.contributionSoftCap.toLong()
        return if (power <= cap) power else cap + (power - cap) / balance.overflowDivisor
    }

    internal fun npcCompensation(
        own: AllianceForce,
        opponent: AllianceForce,
        balance: WeeklyBalance,
    ): Int {
        val missing = (opponent.contributors - own.contributors).coerceAtLeast(0)
        return (missing * balance.npcPerMissingContributor).coerceAtMost(balance.maxNpcCompensation)
    }

    private fun phaseText(index: Int, phaseA: Long, phaseB: Long, codeA: String, codeB: String): String {
        val leader = if (phaseA >= phaseB) codeA else codeB
        return when (index) {
            0 -> "Преимущество — $leader: обнаружено направление главного удара"
            1 -> "Преимущество — $leader: подготовка проведена эффективнее"
            2 -> "Преимущество — $leader: захвачена инициатива в основном столкновении"
            else -> "Преимущество — $leader: ключевой участок удержан"
        }
    }

    private fun deriveSeed(serverSalt: String, key: String): Long {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return ByteBuffer.wrap(mac.doFinal(key.toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, Long.SIZE_BYTES)).long
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
