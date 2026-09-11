package com.tggames.frontline.campaign

import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.replay.ReplayService
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CampaignWorker(
    private val campaigns: CampaignService,
    private val telegram: TelegramClient,
    private val replays: ReplayService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun resolveAndDeliver() {
        val resolved = campaigns.resolveDueCampaigns()
        if (resolved > 0) logger.info("Resolved {} due weekly campaign(s)", resolved)

        var lastNotificationId = 0L
        while (true) {
            val notification = campaigns.nextPendingNotification(lastNotificationId) ?: break
            lastNotificationId = notification.id
            deliver(notification)
        }
    }

    private fun deliver(notification: PendingCampaignNotification) {
        try {
            if (!notification.messageSent) {
                telegram.sendMessage(notification.playerTelegramId, notification.message)
                campaigns.markNotificationMessageSent(notification.id)
            }
            if (!notification.mediaSent) {
                val replay = replays.prepareWeekly(notification.allianceCode, notification.matchupId)
                telegram.sendAnimation(
                    notification.playerTelegramId,
                    replay.url,
                    GameI18n.t(notification.language, "weekly_replay_caption"),
                    replay.width,
                    replay.height,
                    replay.durationSeconds,
                )
                campaigns.markNotificationMediaSent(notification.id)
            }
        } catch (error: TelegramDeliveryException) {
            if (error.playerUnavailable) {
                logger.info("Player {} is no longer reachable by Telegram", notification.playerTelegramId)
                campaigns.markPlayerUnavailable(notification.playerTelegramId, error.javaClass.simpleName)
            } else {
                logger.warn("Campaign notification {} delivery failed: {}", notification.id, error.javaClass.simpleName)
                campaigns.markNotificationFailed(notification.id, error.javaClass.simpleName)
            }
        } catch (error: Exception) {
            logger.warn("Campaign notification {} processing failed: {}", notification.id, error.javaClass.simpleName)
            campaigns.markNotificationFailed(notification.id, error.javaClass.simpleName)
        }
    }
}
