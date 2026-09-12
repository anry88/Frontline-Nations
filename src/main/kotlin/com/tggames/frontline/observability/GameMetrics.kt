package com.tggames.frontline.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class GameMetrics(
    private val registry: MeterRegistry,
    private val jdbc: JdbcClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val playerGauges = PERIODS.associateWith { period -> gauge("frontline.players", "period", period) }
    private val registrationGauges = PERIODS.flatMap { period ->
        REGISTRATION_SOURCES.map { source -> (period to source) to gauge("frontline.registrations", "period", period, "source", source) }
    }.toMap()
    private val paymentGauges = PAYMENT_STATES.associateWith { status -> gauge("frontline.stars.payments", "status", status) }
    private val starAmountGauges = PAYMENT_STATES.associateWith { status -> gauge("frontline.stars.amount", "status", status, "unit", "stars") }
    private val creditAmountGauges = PAYMENT_STATES.associateWith { status -> gauge("frontline.stars.amount", "status", status, "unit", "credits") }
    private val equipmentGauges = EQUIPMENT_ACTIONS.associateWith { action -> gauge("frontline.equipment.transactions", "action", action.lowercase()) }
    private val battleGauges = BATTLE_RESULTS.associateWith { result -> gauge("frontline.battles", "result", result) }

    fun command(command: String, source: String = "message") {
        registry.counter("frontline.bot.command", "command", normalizeCommand(command), "source", source).increment()
    }

    fun callback(data: String) {
        registry.counter("frontline.bot.callback", "action", callbackAction(data)).increment()
    }

    fun registration(source: String) {
        registry.counter("frontline.registration", "source", normalizeRegistrationSource(source)).increment()
    }

    fun stars(stage: String, pack: String? = null) {
        registry.counter("frontline.stars.purchase", "stage", stage, "pack", pack ?: "unknown").increment()
    }

    fun equipment(action: String, code: String?, result: String, quantity: Int = 1) {
        registry.counter(
            "frontline.equipment.action",
            "action", action,
            "unit", code?.lowercase() ?: "unknown",
            "result", result.lowercase(),
        ).increment()
        if (result.equals("SUCCESS", ignoreCase = true)) {
            registry.counter("frontline.equipment.units", "action", action, "unit", code?.lowercase() ?: "unknown")
                .increment(quantity.toDouble())
        }
    }

    fun battle(victory: Boolean) {
        registry.counter("frontline.battle", "mode", "personal", "result", if (victory) "victory" else "defeat").increment()
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    fun refreshDatabaseGauges() {
        runCatching {
            val now = Instant.now()
            playerGauges.getValue("total").set(count("SELECT COUNT(*) FROM players"))
            playerGauges.getValue("day").set(countSince("updated_at", now.minus(1, ChronoUnit.DAYS)))
            playerGauges.getValue("week").set(countSince("updated_at", now.minus(7, ChronoUnit.DAYS)))
            playerGauges.getValue("month").set(countSince("updated_at", now.minus(30, ChronoUnit.DAYS)))

            REGISTRATION_SOURCES.forEach { source ->
                registrationGauges.getValue("total" to source).set(registrationCount(source, null))
                registrationGauges.getValue("day" to source).set(registrationCount(source, now.minus(1, ChronoUnit.DAYS)))
                registrationGauges.getValue("week" to source).set(registrationCount(source, now.minus(7, ChronoUnit.DAYS)))
                registrationGauges.getValue("month" to source).set(registrationCount(source, now.minus(30, ChronoUnit.DAYS)))
            }

            PAYMENT_STATES.forEach { status ->
                val refunded = status == "refunded"
                paymentGauges.getValue(status).set(starsAggregate("COUNT(*)", refunded))
                starAmountGauges.getValue(status).set(starsAggregate("COALESCE(SUM(price_stars), 0)", refunded))
                creditAmountGauges.getValue(status).set(starsAggregate("COALESCE(SUM(credits), 0)", refunded))
            }
            EQUIPMENT_ACTIONS.forEach { action ->
                equipmentGauges.getValue(action).set(
                    jdbc.sql("SELECT COUNT(*) FROM equipment_transactions WHERE action = :action")
                        .param("action", action).query(Long::class.java).single(),
                )
            }
            battleGauges.getValue("victory").set(count("SELECT COUNT(*) FROM battles WHERE victory"))
            battleGauges.getValue("defeat").set(count("SELECT COUNT(*) FROM battles WHERE NOT victory"))
        }.onFailure { error ->
            logger.warn("Could not refresh observability gauges", error)
        }
    }

    private fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    private fun countSince(column: String, cutoff: Instant): Long = jdbc
        .sql("SELECT COUNT(*) FROM players WHERE $column >= :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .query(Long::class.java)
        .single()

    private fun registrationCount(source: String, cutoff: Instant?): Long {
        val query = if (cutoff == null) {
            jdbc.sql("SELECT COUNT(*) FROM players WHERE registration_source = :source")
        } else {
            jdbc.sql("SELECT COUNT(*) FROM players WHERE registration_source = :source AND created_at >= :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
        }
        return query.param("source", source).query(Long::class.java).single()
    }

    private fun starsAggregate(expression: String, refunded: Boolean): Long = jdbc
        .sql("SELECT $expression FROM star_payments WHERE refunded_at IS ${if (refunded) "NOT " else ""}NULL")
        .query(Long::class.java)
        .single()

    private fun gauge(name: String, vararg tags: String): AtomicLong {
        val value = AtomicLong()
        Gauge.builder(name, value) { it.get().toDouble() }.tags(*tags).register(registry)
        return value
    }

    companion object {
        private val PERIODS = listOf("day", "week", "month", "total")
        private val REGISTRATION_SOURCES = listOf("telegram", "referral")
        private val PAYMENT_STATES = listOf("paid", "refunded")
        private val EQUIPMENT_ACTIONS = listOf("PURCHASE", "UPGRADE")
        private val BATTLE_RESULTS = listOf("victory", "defeat")

        fun normalizeRegistrationSource(source: String?): String = if (source == "referral") "referral" else "telegram"

        fun normalizeCommand(raw: String): String {
            val command = raw.trim().substringBefore('@').removePrefix("/").lowercase()
            return command.takeIf { it in KNOWN_COMMANDS } ?: "unknown"
        }

        fun callbackAction(data: String): String = when {
            data.startsWith("country:") -> "country"
            data.startsWith("language:") -> "language"
            data.startsWith("nickname:") -> "nickname"
            data.startsWith("nav:") -> data.substringAfter("nav:").substringBefore(':').takeIf { it in NAV_ACTIONS } ?: "navigation"
            data.startsWith("stars:") -> "stars"
            data.startsWith("rank:") -> "rankings"
            data.startsWith("guide:") -> "guide"
            data.startsWith("front:") -> "front"
            data.startsWith("op:") || data.startsWith("deploy:") || data.startsWith("objective:") || data.startsWith("fight:") -> "battle"
            data.startsWith("replay:") -> "replay"
            data.startsWith("shop:") -> "shop"
            data.startsWith("unit:upgrade") -> "upgrade"
            data.startsWith("army:") -> "army"
            else -> "unknown"
        }

        private val KNOWN_COMMANDS = setOf(
            "start", "battle", "army", "hangar", "shop", "stars", "buycredits", "paysupport", "answer",
            "upgrade", "daily", "profile", "front", "contribute", "rankings", "rating", "ratings", "guide",
            "language", "nickname", "country", "settings", "help", "refund", "reject", "ask",
        )
        private val NAV_ACTIONS = setOf("battle", "army", "shop", "upgrade", "daily", "profile", "front", "rankings", "guide", "settings")
    }
}
