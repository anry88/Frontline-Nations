package com.tggames.frontline.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("frontline")
data class FrontlineProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val gameTimezone: String = "Europe/Belgrade",
    val battleServerSalt: String = "local-development-only",
    val telegram: Telegram = Telegram(),
    val campaign: Campaign = Campaign(),
) {
    data class Telegram(
        val botToken: String = "",
        val webhookSecret: String = "",
    )

    data class Campaign(
        val resolveCron: String = "0 0 15 * * SUN",
        val retryCron: String = "0 5/10 * * * *",
        val openCron: String = "0 5 0 * * MON",
        val maxTicks: Int = 96,
        val objectiveBasePoints: Int = 1_000,
        val objectiveDecayPerTick: Int = 15,
        val objectiveMinPoints: Int = 200,
        val survivorScorePercent: Int = 50,
        val npcMinCp: Int = 10,
        val npcMaxCp: Int = 25,
        val winnerXp: Int = 600,
        val loserXp: Int = 250,
        val winnerCredits: Int = 90,
        val loserCredits: Int = 30,
        val winnerMaterials: Int = 30,
        val loserMaterials: Int = 16,
        val destroyedCreditsPer100Power: Int = 1,
        val destroyedXpPer100Power: Int = 50,
        val captureCredits: Int = 5,
        val captureXp: Int = 100,
        val victoryBonusPercent: Int = 120,
        val victoryBonusDays: Long = 7,
    )
}
