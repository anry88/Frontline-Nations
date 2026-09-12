package com.tggames.frontline.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

class GameMetricsTest {
    @Test
    fun `event metrics use bounded player action labels`() {
        val registry = SimpleMeterRegistry()
        val metrics = GameMetrics(registry, mock(JdbcClient::class.java))

        metrics.command("/battle@frontline_nations_bot")
        metrics.command("/anything-user-controlled")
        metrics.callback("shop:buy:MBT:25")
        metrics.registration("referral")
        metrics.stars("completed", "credits_500")
        metrics.equipment("purchase", "MBT", "SUCCESS", 5)
        metrics.battle(true)

        assertThat(registry.get("frontline.bot.command").tags("command", "battle", "source", "message").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("frontline.bot.command").tags("command", "unknown", "source", "message").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("frontline.bot.callback").tag("action", "shop").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("frontline.registration").tag("source", "referral").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("frontline.stars.purchase").tags("stage", "completed", "pack", "credits_500").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("frontline.equipment.units").tags("action", "purchase", "unit", "mbt").counter().count()).isEqualTo(5.0)
        assertThat(registry.get("frontline.battle").tags("mode", "personal", "result", "victory").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `registration source is reduced to direct telegram or referral`() {
        assertThat(GameMetrics.normalizeRegistrationSource("referral")).isEqualTo("referral")
        assertThat(GameMetrics.normalizeRegistrationSource("campaign-secret-value")).isEqualTo("telegram")
        assertThat(GameMetrics.normalizeRegistrationSource(null)).isEqualTo("telegram")
        assertThat(GameMetrics.normalizeRegistrationReferral(" RiverKing ")).isEqualTo("riverking")
        assertThat(GameMetrics.normalizeRegistrationReferral("invalid payload!")).isEqualTo("other")
        assertThat(GameMetrics.normalizeRegistrationReferral(" ")).isNull()
    }

    @Test
    fun `database gauges refresh with timestamp cutoffs`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:game_metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        val jdbc = JdbcClient.create(dataSource)
        listOf(
            "CREATE TABLE players(id BIGINT PRIMARY KEY, alliance_code VARCHAR(8), registration_source VARCHAR(16), registration_referral VARCHAR(64), created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)",
            "CREATE TABLE star_payments(id BIGINT PRIMARY KEY, price_stars BIGINT, credits BIGINT, refunded_at TIMESTAMP WITH TIME ZONE)",
            "CREATE TABLE equipment_transactions(id BIGINT PRIMARY KEY, action VARCHAR(16))",
            "CREATE TABLE battles(id BIGINT PRIMARY KEY, victory BOOLEAN)",
            "INSERT INTO players VALUES (1, 'RS', 'referral', 'riverking', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            "INSERT INTO players VALUES (2, 'US', 'telegram', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            "INSERT INTO players VALUES (3, 'RS', 'telegram', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            "INSERT INTO star_payments VALUES (1, 20, 100, NULL)",
            "INSERT INTO equipment_transactions VALUES (1, 'PURCHASE')",
            "INSERT INTO battles VALUES (1, TRUE)",
        ).forEach { jdbc.sql(it).update() }
        val registry = SimpleMeterRegistry()

        GameMetrics(registry, jdbc).refreshDatabaseGauges()

        assertThat(registry.get("frontline.players").tag("period", "day").gauge().value()).isEqualTo(3.0)
        assertThat(
            registry.get("frontline.players.by.country")
                .tags("country_code", "RS", "country", "Serbia")
                .gauge().value(),
        ).isEqualTo(2.0)
        assertThat(registry.get("frontline.players.by.country").gauges().sumOf { it.value() }).isEqualTo(3.0)
        assertThat(
            registry.get("frontline.registrations")
                .tags("period", "day", "source", "referral", "referral", "riverking")
                .gauge().value(),
        ).isEqualTo(1.0)
        assertThat(
            registry.get("frontline.registrations")
                .tags("period", "day", "source", "telegram", "referral", "direct")
                .gauge().value(),
        ).isEqualTo(2.0)
        assertThat(registry.get("frontline.stars.payments").tag("status", "paid").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("frontline.equipment.transactions").tag("action", "purchase").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("frontline.battles").tag("result", "victory").gauge().value()).isEqualTo(1.0)
    }
}
