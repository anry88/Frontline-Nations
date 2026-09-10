package com.tggames.frontline.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(FrontlineProperties::class)
class AppConfiguration {
    @Bean
    fun telegramRestClient(builder: RestClient.Builder, properties: FrontlineProperties): RestClient {
        val token = properties.telegram.botToken.ifBlank { "disabled" }
        return builder.baseUrl("https://api.telegram.org/bot$token").build()
    }
}
