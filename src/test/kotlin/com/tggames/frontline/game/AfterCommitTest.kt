package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

class AfterCommitTest {
    @Test
    fun `result delivery runs only after the game transaction commits`() {
        val transaction = TransactionTemplate(
            DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:after_commit;DB_CLOSE_DELAY=-1")),
        )
        var delivered = false

        transaction.executeWithoutResult {
            runAfterCommit { delivered = true }
            assertThat(delivered).isFalse()
        }

        assertThat(delivered).isTrue()
    }

    @Test
    fun `result delivery is skipped when the game transaction rolls back`() {
        val transaction = TransactionTemplate(
            DataSourceTransactionManager(DriverManagerDataSource("jdbc:h2:mem:after_rollback;DB_CLOSE_DELAY=-1")),
        )
        var delivered = false

        runCatching {
            transaction.executeWithoutResult {
                runAfterCommit { delivered = true }
                error("rollback")
            }
        }

        assertThat(delivered).isFalse()
    }
}
