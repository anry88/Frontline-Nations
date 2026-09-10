package com.tggames.frontline.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("frontline")
data class FrontlineProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val gameTimezone: String = "Europe/Belgrade",
    val battleServerSalt: String = "local-development-only",
    val telegram: Telegram = Telegram(),
) {
    data class Telegram(
        val botToken: String = "",
        val webhookSecret: String = "",
    )
}
