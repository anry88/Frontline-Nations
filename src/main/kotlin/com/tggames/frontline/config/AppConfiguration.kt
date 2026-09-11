package com.tggames.frontline.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.web.client.RestClient
import java.time.Clock

@Configuration
@EnableConfigurationProperties(FrontlineProperties::class)
class AppConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun telegramRestClient(builder: RestClient.Builder, properties: FrontlineProperties): RestClient {
        val token = properties.telegram.botToken.ifBlank { "disabled" }
        return builder.baseUrl("https://api.telegram.org/bot$token").build()
    }

    @Bean("campaignWorkerExecutor")
    fun campaignWorkerExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 1
        setThreadNamePrefix("campaign-worker-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }
}
