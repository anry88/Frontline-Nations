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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class GameMetrics(
    private val registry: MeterRegistry,
    private val jdbc: JdbcClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val playerGauges = PERIODS.associateWith { period -> gauge("frontline.players", "period", period) }
    private val registrationGauges = ConcurrentHashMap<RegistrationGaugeKey, AtomicLong>()
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

            refreshRegistrationGauges("total", null)
            refreshRegistrationGauges("day", now.minus(1, ChronoUnit.DAYS))
            refreshRegistrationGauges("week", now.minus(7, ChronoUnit.DAYS))
            refreshRegistrationGauges("month", now.minus(30, ChronoUnit.DAYS))

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

    private fun refreshRegistrationGauges(period: String, cutoff: Instant?) {
        val baseSql = """
            SELECT registration_source,
                   COALESCE(registration_referral, 'unknown') AS registration_referral,
                   COUNT(*) AS registrations
              FROM players
        """.trimIndent()
        val query = if (cutoff == null) {
            jdbc.sql("$baseSql GROUP BY registration_source, registration_referral")
        } else {
            jdbc.sql("$baseSql WHERE created_at >= :cutoff GROUP BY registration_source, registration_referral")
                .param("cutoff", Timestamp.from(cutoff))
        }
        val rows = query.query { result, _ ->
            RegistrationCount(
                source = normalizeRegistrationSource(result.getString("registration_source")),
                referral = result.getString("registration_referral"),
                count = result.getLong("registrations"),
            )
        }.list()
        val direct = rows.filter { it.source == "telegram" }.sumOf { it.count }
        val referrals = rows.filter { it.source == "referral" }
            .sortedWith(compareByDescending<RegistrationCount> { it.count }.thenBy { it.referral })
        val tracked = referrals.take(MAX_REFERRAL_SERIES)
        val other = referrals.drop(MAX_REFERRAL_SERIES).sumOf { it.count }
        val values = buildMap {
            put(RegistrationGaugeKey(period, "telegram", "direct"), direct)
            tracked.forEach { put(RegistrationGaugeKey(period, it.source, it.referral), it.count) }
            if (other > 0) {
                val otherKey = RegistrationGaugeKey(period, "referral", "other")
                put(otherKey, getOrDefault(otherKey, 0) + other)
            }
        }

        registrationGauges.keys.filter { it.period == period && it !in values }.forEach { key ->
            registrationGauges.remove(key)?.set(0)
            registry.find("frontline.registrations")
                .tags("period", key.period, "source", key.source, "referral", key.referral)
                .gauge()
                ?.let(registry::remove)
        }
        values.forEach { (key, value) ->
            registrationGauges.computeIfAbsent(key) {
                gauge(
                    "frontline.registrations",
                    "period", key.period,
                    "source", key.source,
                    "referral", key.referral,
                )
            }.set(value)
        }
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
        private val PAYMENT_STATES = listOf("paid", "refunded")
        private val EQUIPMENT_ACTIONS = listOf("PURCHASE", "UPGRADE")
        private val BATTLE_RESULTS = listOf("victory", "defeat")

        fun normalizeRegistrationSource(source: String?): String = if (source == "referral") "referral" else "telegram"

        fun normalizeRegistrationReferral(raw: String?): String? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return null
            return normalized.takeIf(REGISTRATION_REFERRAL_PATTERN::matches) ?: "other"
        }

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
        private val REGISTRATION_REFERRAL_PATTERN = Regex("[a-z0-9_-]{1,64}")
        private const val MAX_REFERRAL_SERIES = 24
    }

    private data class RegistrationGaugeKey(val period: String, val source: String, val referral: String)
    private data class RegistrationCount(val source: String, val referral: String, val count: Long)
}
