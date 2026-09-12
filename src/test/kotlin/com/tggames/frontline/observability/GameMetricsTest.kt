package com.tggames.frontline.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient

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
    }
}
