package com.tggames.frontline.monetization

import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramPreCheckoutQuery
import com.tggames.frontline.telegram.TelegramSuccessfulPayment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

@Service
class StarsPaymentService(
    private val jdbc: JdbcClient,
    private val telegram: TelegramClient,
    private val properties: FrontlineProperties,
) {
    fun validate(query: TelegramPreCheckoutQuery): PreCheckoutValidation {
        val payload = StarsCreditCatalog.parsePayload(query.invoicePayload)
            ?: return PreCheckoutValidation(false, "invalid_payload")
        val pack = StarsCreditCatalog.find(payload.packId)
            ?: return PreCheckoutValidation(false, "unknown_pack")
        val playerExists = jdbc.sql("SELECT COUNT(*) FROM players WHERE telegram_id = :telegramId")
            .param("telegramId", payload.telegramId)
            .query(Int::class.java)
            .single() > 0
        val valid = playerExists &&
            payload.telegramId == query.from.id &&
            query.currency == StarsCreditCatalog.CURRENCY &&
            query.totalAmount == pack.priceStars
        return PreCheckoutValidation(valid, if (valid) null else "payment_mismatch")
    }

    @Transactional
    fun deliver(telegramId: Long, payment: TelegramSuccessfulPayment): PaymentDelivery {
        val payload = StarsCreditCatalog.parsePayload(payment.invoicePayload)
            ?: return PaymentDelivery.Invalid("invalid_payload")
        val pack = StarsCreditCatalog.find(payload.packId)
            ?: return PaymentDelivery.Invalid("unknown_pack")
        if (
            payload.telegramId != telegramId ||
            payment.currency != StarsCreditCatalog.CURRENCY ||
            payment.totalAmount != pack.priceStars
        ) {
            return PaymentDelivery.Invalid("payment_mismatch")
        }

        val existing = paymentByCharge(payment.telegramPaymentChargeId)
        if (existing != null) return PaymentDelivery.Duplicate(existing.id, existing.credits)

        val ledgerReferenceId = UUID.randomUUID()
        val created = jdbc.sql(
            """
            INSERT INTO star_payments(
                player_telegram_id, pack_id, credits, price_stars, currency, invoice_payload,
                telegram_payment_charge_id, provider_payment_charge_id, ledger_reference_id
            )
            VALUES (
                :telegramId, :packId, :credits, :priceStars, :currency, :payload,
                :telegramChargeId, :providerChargeId, :ledgerReferenceId
            )
            ON CONFLICT (telegram_payment_charge_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
        ).param("telegramId", telegramId)
            .param("packId", pack.id)
            .param("credits", pack.credits)
            .param("priceStars", pack.priceStars)
            .param("currency", payment.currency)
            .param("payload", payment.invoicePayload)
            .param("telegramChargeId", payment.telegramPaymentChargeId)
            .param("providerChargeId", payment.providerPaymentChargeId, Types.VARCHAR)
            .param("ledgerReferenceId", ledgerReferenceId)
            .query(Long::class.java)
            .optional()

        if (created.isEmpty) {
            val duplicate = paymentByCharge(payment.telegramPaymentChargeId)
                ?: error("Concurrent Stars payment was not readable")
            return PaymentDelivery.Duplicate(duplicate.id, duplicate.credits)
        }

        jdbc.sql("UPDATE players SET credits = credits + :credits, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :telegramId")
            .param("credits", pack.credits)
            .param("telegramId", telegramId)
            .update()
        jdbc.sql(
            """
            INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id)
            VALUES (:telegramId, 'CREDITS', :credits, 'STARS_PURCHASE', :referenceId)
            """.trimIndent(),
        ).param("telegramId", telegramId)
            .param("credits", pack.credits)
            .param("referenceId", ledgerReferenceId)
            .update()
        val balance = jdbc.sql("SELECT credits FROM players WHERE telegram_id = :telegramId")
            .param("telegramId", telegramId)
            .query(Long::class.java)
            .single()
        return PaymentDelivery.Delivered(created.get(), pack, balance)
    }

    fun refundablePayments(telegramId: Long): List<UserStarsPayment> = jdbc.sql(
        """
        SELECT id, pack_id, credits, price_stars, currency, created_at
          FROM star_payments
         WHERE player_telegram_id = :telegramId
           AND refunded_at IS NULL
         ORDER BY created_at DESC, id DESC
        """.trimIndent(),
    ).param("telegramId", telegramId).query(UserStarsPayment::class.java).list()

    @Transactional
    fun createSupportRequest(telegramId: Long, paymentId: Long, reason: String): SupportCreation {
        val payment = refundablePayments(telegramId).firstOrNull { it.id == paymentId }
            ?: return SupportCreation.PaymentNotFound
        val requestId = jdbc.sql(
            """
            INSERT INTO star_payment_support_requests(player_telegram_id, payment_id, reason, status)
            VALUES (:telegramId, :paymentId, :reason, 'pending')
            ON CONFLICT (payment_id) WHERE status IN ('pending', 'info_requested', 'refunding') DO NOTHING
            RETURNING id
            """.trimIndent(),
        ).param("telegramId", telegramId)
            .param("paymentId", paymentId)
            .param("reason", reason.take(MAX_SUPPORT_TEXT))
            .query(Long::class.java)
            .optional()
        if (requestId.isPresent) return SupportCreation.Created(requestId.get(), payment)
        val openId = jdbc.sql(
            """
            SELECT id FROM star_payment_support_requests
             WHERE payment_id = :paymentId AND status IN ('pending', 'info_requested', 'refunding')
            """.trimIndent(),
        ).param("paymentId", paymentId).query(Long::class.java).single()
        return SupportCreation.AlreadyOpen(openId)
    }

    fun adminChatId(): Long? = properties.telegram.adminChatId.takeIf { it != 0L }

    fun isAdminContext(chatId: Long, actorId: Long): Boolean = adminChatId()?.let { it == chatId && it == actorId } == true

    @Transactional
    fun refund(requestId: Long): AdminSupportResult {
        val row = supportRequest(requestId, lock = true) ?: return AdminSupportResult.NotFound
        if (row.refundedAt != null || row.status in RESOLVED_STATUSES) return AdminSupportResult.AlreadyResolved
        telegram.refundStarPayment(row.playerTelegramId, row.telegramPaymentChargeId)
        val ledgerReferenceId = UUID.randomUUID()
        jdbc.sql("UPDATE star_payments SET refunded_at = CURRENT_TIMESTAMP WHERE id = :paymentId AND refunded_at IS NULL")
            .param("paymentId", row.paymentId)
            .update()
        jdbc.sql("UPDATE players SET credits = credits - :credits, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :telegramId")
            .param("credits", row.credits)
            .param("telegramId", row.playerTelegramId)
            .update()
        jdbc.sql(
            """
            INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id)
            VALUES (:telegramId, 'CREDITS', :delta, 'STARS_REFUND', :referenceId)
            """.trimIndent(),
        ).param("telegramId", row.playerTelegramId)
            .param("delta", -row.credits.toLong())
            .param("referenceId", ledgerReferenceId)
            .update()
        resolveRequest(requestId, "refunded", null)
        return AdminSupportResult.Updated(row.playerTelegramId)
    }

    @Transactional
    fun reject(requestId: Long, reason: String): AdminSupportResult {
        val row = supportRequest(requestId) ?: return AdminSupportResult.NotFound
        if (row.status in RESOLVED_STATUSES) return AdminSupportResult.AlreadyResolved
        resolveRequest(requestId, "rejected", reason.take(MAX_SUPPORT_TEXT))
        return AdminSupportResult.Updated(row.playerTelegramId)
    }

    @Transactional
    fun ask(requestId: Long, question: String): AdminSupportResult {
        val row = supportRequest(requestId) ?: return AdminSupportResult.NotFound
        if (row.status in RESOLVED_STATUSES) return AdminSupportResult.AlreadyResolved
        jdbc.sql(
            """
            UPDATE star_payment_support_requests
               SET status = 'info_requested', admin_message = :message, updated_at = CURRENT_TIMESTAMP
             WHERE id = :id
            """.trimIndent(),
        ).param("message", question.take(MAX_SUPPORT_TEXT)).param("id", requestId).update()
        return AdminSupportResult.Updated(row.playerTelegramId)
    }

    @Transactional
    fun answer(telegramId: Long, requestId: Long?, answer: String): AnswerResult {
        val row = if (requestId != null) {
            jdbc.sql(
                """
                SELECT id FROM star_payment_support_requests
                 WHERE id = :id AND player_telegram_id = :telegramId AND status = 'info_requested'
                """.trimIndent(),
            ).param("id", requestId).param("telegramId", telegramId).query(Long::class.java).optional()
        } else {
            jdbc.sql(
                """
                SELECT id FROM star_payment_support_requests
                 WHERE player_telegram_id = :telegramId AND status = 'info_requested'
                 ORDER BY updated_at DESC LIMIT 1
                """.trimIndent(),
            ).param("telegramId", telegramId).query(Long::class.java).optional()
        }
        if (row.isEmpty) return AnswerResult.NotFound
        jdbc.sql(
            """
            UPDATE star_payment_support_requests
               SET status = 'pending', player_message = :answer, updated_at = CURRENT_TIMESTAMP
             WHERE id = :id
            """.trimIndent(),
        ).param("answer", answer.take(MAX_SUPPORT_TEXT)).param("id", row.get()).update()
        return AnswerResult.Accepted(row.get())
    }

    private fun paymentByCharge(chargeId: String): ExistingPayment? = jdbc.sql(
        "SELECT id, credits FROM star_payments WHERE telegram_payment_charge_id = :chargeId",
    ).param("chargeId", chargeId).query(ExistingPayment::class.java).optional().orElse(null)

    private fun supportRequest(requestId: Long, lock: Boolean = false): SupportRow? = jdbc.sql(
        """
        SELECT request.id, request.status, payment.id AS payment_id, payment.player_telegram_id,
               payment.credits, payment.telegram_payment_charge_id, payment.refunded_at
          FROM star_payment_support_requests request
          JOIN star_payments payment ON payment.id = request.payment_id
         WHERE request.id = :id${if (lock) " FOR UPDATE" else ""}
        """.trimIndent(),
    ).param("id", requestId).query(SupportRow::class.java).optional().orElse(null)

    private fun resolveRequest(requestId: Long, status: String, message: String?) {
        jdbc.sql(
            """
            UPDATE star_payment_support_requests
               SET status = :status, admin_message = :message,
                   updated_at = CURRENT_TIMESTAMP, resolved_at = CURRENT_TIMESTAMP
             WHERE id = :id
            """.trimIndent(),
        ).param("status", status)
            .param("message", message, Types.VARCHAR)
            .param("id", requestId)
            .update()
    }

    companion object {
        private const val MAX_SUPPORT_TEXT = 1_000
        private val RESOLVED_STATUSES = setOf("refunded", "rejected")
    }
}

data class PreCheckoutValidation(val valid: Boolean, val reason: String?)

sealed interface PaymentDelivery {
    data class Delivered(val paymentId: Long, val pack: StarsCreditPack, val creditsBalance: Long) : PaymentDelivery
    data class Duplicate(val paymentId: Long, val credits: Int) : PaymentDelivery
    data class Invalid(val reason: String) : PaymentDelivery
}

data class UserStarsPayment(
    val id: Long,
    val packId: String,
    val credits: Int,
    val priceStars: Int,
    val currency: String,
    val createdAt: OffsetDateTime,
)

sealed interface SupportCreation {
    data class Created(val requestId: Long, val payment: UserStarsPayment) : SupportCreation
    data class AlreadyOpen(val requestId: Long) : SupportCreation
    data object PaymentNotFound : SupportCreation
}

sealed interface AdminSupportResult {
    data class Updated(val playerTelegramId: Long) : AdminSupportResult
    data object NotFound : AdminSupportResult
    data object AlreadyResolved : AdminSupportResult
}

sealed interface AnswerResult {
    data class Accepted(val requestId: Long) : AnswerResult
    data object NotFound : AnswerResult
}

private data class ExistingPayment(val id: Long, val credits: Int)

private data class SupportRow(
    val id: Long,
    val status: String,
    val paymentId: Long,
    val playerTelegramId: Long,
    val credits: Int,
    val telegramPaymentChargeId: String,
    val refundedAt: OffsetDateTime?,
)
