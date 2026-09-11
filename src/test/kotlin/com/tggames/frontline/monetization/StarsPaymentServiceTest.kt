package com.tggames.frontline.monetization

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramPreCheckoutQuery
import com.tggames.frontline.telegram.TelegramUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

class StarsPaymentServiceTest {
    private val dataSource = DriverManagerDataSource("jdbc:h2:mem:stars-validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        .also { source ->
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE IF NOT EXISTS players(telegram_id BIGINT PRIMARY KEY)")
                    statement.execute("MERGE INTO players KEY(telegram_id) VALUES (42)")
                }
            }
        }
    private val service = StarsPaymentService(
        JdbcClient.create(dataSource),
        mock(TelegramClient::class.java),
        FrontlineProperties(),
    )

    @Test
    fun `pre-checkout accepts only matching player pack currency and amount`() {
        val valid = query()

        assertThat(service.validate(valid).valid).isTrue()
        assertThat(service.validate(valid.copy(totalAmount = 84)).valid).isFalse()
        assertThat(service.validate(valid.copy(currency = "USD")).valid).isFalse()
        assertThat(service.validate(valid.copy(from = valid.from.copy(id = 43))).valid).isFalse()
        assertThat(service.validate(valid.copy(invoicePayload = "pack=credits_unknown;user=42")).valid).isFalse()
    }

    @Test
    fun `admin commands require the configured private chat and same actor`() {
        val configured = StarsPaymentService(
            JdbcClient.create(dataSource),
            mock(TelegramClient::class.java),
            FrontlineProperties(telegram = FrontlineProperties.Telegram(adminChatId = 77)),
        )

        assertThat(configured.isAdminContext(chatId = 77, actorId = 77)).isTrue()
        assertThat(configured.isAdminContext(chatId = 77, actorId = 78)).isFalse()
        assertThat(configured.isAdminContext(chatId = 78, actorId = 77)).isFalse()
    }

    private fun query() = TelegramPreCheckoutQuery(
        id = "checkout-1",
        from = TelegramUser(42, "Commander"),
        currency = "XTR",
        totalAmount = 85,
        invoicePayload = StarsCreditCatalog.payload(42, "credits_500"),
    )
}
