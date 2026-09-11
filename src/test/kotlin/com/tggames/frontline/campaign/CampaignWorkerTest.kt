package com.tggames.frontline.campaign

import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.replay.ReplayArtifact
import com.tggames.frontline.replay.ReplayService
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class CampaignWorkerTest {
    private val campaigns = mock(CampaignService::class.java)
    private val telegram = mock(TelegramClient::class.java)
    private val replays = mock(ReplayService::class.java)
    private val worker = CampaignWorker(campaigns, telegram, replays)

    @Test
    fun `delivers result then replay and completes both outbox stages`() {
        val notification = notification(7)
        val replay = ReplayArtifact("https://example.test/replay.mp4", 768, 768, 42)
        `when`(campaigns.nextPendingNotification(0)).thenReturn(notification)
        `when`(campaigns.nextPendingNotification(7)).thenReturn(null)
        `when`(replays.prepareWeekly(notification.allianceCode, notification.matchupId)).thenReturn(replay)

        worker.resolveAndDeliver()

        val order = inOrder(telegram, campaigns, replays)
        order.verify(telegram).sendMessage(notification.playerTelegramId, notification.message, null)
        order.verify(campaigns).markNotificationMessageSent(notification.id)
        order.verify(replays).prepareWeekly(notification.allianceCode, notification.matchupId)
        order.verify(telegram).sendAnimation(
            notification.playerTelegramId,
            replay.url,
            GameI18n.t(notification.language, "weekly_replay_caption"),
            replay.width,
            replay.height,
            replay.durationSeconds,
            null,
        )
        order.verify(campaigns).markNotificationMediaSent(notification.id)
    }

    @Test
    fun `permanent Telegram failure marks player unavailable without rendering`() {
        val notification = notification(11)
        `when`(campaigns.nextPendingNotification(0)).thenReturn(notification)
        `when`(campaigns.nextPendingNotification(11)).thenReturn(null)
        doThrow(TelegramDeliveryException(true, "sendMessage", IllegalStateException("blocked")))
            .`when`(telegram).sendMessage(notification.playerTelegramId, notification.message, null)

        worker.resolveAndDeliver()

        verify(campaigns).markPlayerUnavailable(notification.playerTelegramId, TelegramDeliveryException::class.java.simpleName)
        verify(replays, never()).prepareWeekly(notification.allianceCode, notification.matchupId)
        verify(campaigns, never()).markNotificationFailed(anyLong(), org.mockito.ArgumentMatchers.anyString())
    }

    private fun notification(id: Long) = PendingCampaignNotification(
        id = id,
        playerTelegramId = 42,
        message = "Weekly result",
        matchupId = UUID.randomUUID(),
        allianceCode = "RS",
        language = GameLanguage.EN,
        messageSent = false,
        mediaSent = false,
    )
}
