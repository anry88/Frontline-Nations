package com.tggames.frontline.campaign

import com.tggames.frontline.telegram.TelegramClient
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CampaignScheduler(
    private val campaigns: CampaignService,
    private val telegram: TelegramClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${frontline.campaign.open-cron}", zone = "\${frontline.game-timezone}")
    fun openWeek() {
        try {
            campaigns.ensureCurrentWeek()
        } catch (error: Exception) {
            logger.error("Weekly campaign opening failed", error)
        }
    }

    @Scheduled(cron = "\${frontline.campaign.resolve-cron}", zone = "\${frontline.game-timezone}")
    fun resolveAtScheduledTime() {
        resolveAndNotify()
    }

    @Scheduled(cron = "\${frontline.campaign.retry-cron}", zone = "\${frontline.game-timezone}")
    fun recoverMissedRunAndDeliverNotifications() {
        resolveAndNotify()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverAfterRestart() {
        resolveAndNotify()
    }

    private fun resolveAndNotify() {
        try {
            val resolved = campaigns.resolveDueCampaigns()
            if (resolved > 0) logger.info("Resolved {} due weekly campaign(s)", resolved)
            campaigns.pendingNotifications().forEach { notification ->
                try {
                    telegram.sendMessage(notification.playerTelegramId, notification.message)
                    campaigns.markNotificationSent(notification.id)
                } catch (error: Exception) {
                    logger.warn("Campaign notification {} delivery failed: {}", notification.id, error.javaClass.simpleName)
                    campaigns.markNotificationFailed(notification.id, error.javaClass.simpleName)
                }
            }
        } catch (error: Exception) {
            logger.error("Weekly campaign recovery failed", error)
        }
    }
}
