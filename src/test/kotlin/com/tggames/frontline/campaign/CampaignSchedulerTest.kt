package com.tggames.frontline.campaign

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.core.task.TaskExecutor

class CampaignSchedulerTest {
    @Test
    fun `opening trigger prepares the currently contributable week`() {
        val campaigns = mock(CampaignService::class.java)
        val worker = mock(CampaignWorker::class.java)
        val scheduler = CampaignScheduler(campaigns, worker, TaskExecutor(Runnable::run))

        scheduler.openWeek()

        verify(campaigns).ensureContributionWeek()
        verify(campaigns, never()).ensureCurrentWeek()
    }

    @Test
    fun `scheduled trigger queues one background worker without resolving inline`() {
        val campaigns = mock(CampaignService::class.java)
        val worker = mock(CampaignWorker::class.java)
        val tasks = mutableListOf<Runnable>()
        val executor = TaskExecutor(tasks::add)
        val scheduler = CampaignScheduler(campaigns, worker, executor)

        scheduler.resolveAtScheduledTime()
        scheduler.recoverMissedRunAndDeliverNotifications()

        assertThat(tasks).hasSize(1)
        verify(worker, never()).resolveAndDeliver()
        tasks.single().run()
        verify(worker).resolveAndDeliver()
    }
}
