package com.tggames.frontline.campaign

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CampaignScheduler(
    private val campaigns: CampaignService,
    private val worker: CampaignWorker,
    @param:Qualifier("campaignWorkerExecutor") private val executor: TaskExecutor,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workerRunning = AtomicBoolean(false)

    @Scheduled(cron = "\${frontline.campaign.open-cron}", zone = "\${frontline.game-timezone}")
    fun openWeek() {
        try {
            campaigns.ensureContributionWeek()
        } catch (error: Exception) {
            logger.error("Weekly campaign opening failed", error)
        }
    }

    @Scheduled(cron = "\${frontline.campaign.resolve-cron}", zone = "\${frontline.game-timezone}")
    fun resolveAtScheduledTime() {
        scheduleWorker()
    }

    @Scheduled(cron = "\${frontline.campaign.retry-cron}", zone = "\${frontline.game-timezone}")
    fun recoverMissedRunAndDeliverNotifications() {
        scheduleWorker()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverAfterRestart() {
        scheduleWorker()
    }

    private fun scheduleWorker() {
        if (!workerRunning.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    worker.resolveAndDeliver()
                } catch (error: Exception) {
                    logger.error("Weekly campaign worker failed", error)
                } finally {
                    workerRunning.set(false)
                }
            }
        } catch (error: Exception) {
            workerRunning.set(false)
            logger.error("Could not schedule weekly campaign worker", error)
        }
    }
}
