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
        val npcBasePower: Int = 500,
        val npcPerMissingContributor: Int = 150,
        val maxNpcCompensation: Int = 900,
        val contributionSoftCap: Int = 5_000,
        val overflowDivisor: Int = 4,
        val winnerXp: Int = 300,
        val loserXp: Int = 180,
        val winnerCredits: Int = 250,
        val loserCredits: Int = 140,
        val winnerResearch: Int = 20,
        val loserResearch: Int = 10,
        val winnerMaterials: Int = 30,
        val loserMaterials: Int = 16,
    )
}
