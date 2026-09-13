package com.tggames.frontline.campaign

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test

class CampaignResolutionBatchTest {
    @Test
    fun `simulates outside transactions and persists each matchup independently`() {
        var inTransaction = false
        val events = mutableListOf<String>()
        val batch = CampaignResolutionBatch { action ->
            check(!inTransaction)
            events += "begin"
            inTransaction = true
            try {
                action()
                events += "commit"
            } finally {
                inTransaction = false
            }
        }

        batch.resolve(
            listOf(1, 2),
            simulate = { input ->
                check(!inTransaction)
                events += "simulate-$input"
                input * 10
            },
            persist = { input, result ->
                check(inTransaction)
                events += "persist-$input-$result"
            },
        )

        assertThat(events).containsExactly(
            "simulate-1", "begin", "persist-1-10", "commit",
            "simulate-2", "begin", "persist-2-20", "commit",
        )
        assertThat(inTransaction).isFalse()
    }

    @Test
    fun `does not start later simulations when a matchup commit fails`() {
        val simulated = mutableListOf<Int>()
        val batch = CampaignResolutionBatch { action -> action() }

        assertThatIllegalStateException().isThrownBy {
            batch.resolve(
                listOf(1, 2),
                simulate = { it.also(simulated::add) },
                persist = { _, _ -> error("commit failed") },
            )
        }

        assertThat(simulated).containsExactly(1)
    }
}
