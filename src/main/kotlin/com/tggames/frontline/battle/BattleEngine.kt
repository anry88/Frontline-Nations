package com.tggames.frontline.battle

import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

data class BattleResult(
    val victory: Boolean,
    val playerPower: Int,
    val enemyPower: Int,
    val xp: Int,
    val credits: Int,
    val materials: Int,
    val seedHash: String,
)

@Component
class BattleEngine {
    fun resolve(serverSalt: String, battleKey: String, commanderLevel: Int): BattleResult {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(serverSalt.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(battleKey.toByteArray(StandardCharsets.UTF_8))
        val seed = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long
        val random = Random(seed)

        val playerPower = 55 + commanderLevel * 4 + random.nextInt(0, 31)
        val enemyPower = 58 + commanderLevel * 3 + random.nextInt(0, 31)
        val victory = playerPower >= enemyPower
        val xp = if (victory) 120 + random.nextInt(0, 61) else 55 + random.nextInt(0, 31)
        val credits = if (victory) 140 + random.nextInt(0, 81) else 60 + random.nextInt(0, 41)
        val materials = if (victory) 8 + random.nextInt(0, 7) else 3 + random.nextInt(0, 4)

        return BattleResult(
            victory = victory,
            playerPower = playerPower,
            enemyPower = enemyPower,
            xp = xp,
            credits = credits,
            materials = materials,
            seedHash = MessageDigest.getInstance("SHA-256").digest(digest).toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
