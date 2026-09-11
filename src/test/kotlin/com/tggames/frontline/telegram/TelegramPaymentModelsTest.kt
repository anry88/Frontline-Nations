package com.tggames.frontline.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TelegramPaymentModelsTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `deserializes pre-checkout and successful payment updates`() {
        val preCheckout = mapper.readValue(
            """
            {"update_id":1,"pre_checkout_query":{"id":"q1","from":{"id":42,"first_name":"A"},"currency":"XTR","total_amount":20,"invoice_payload":"pack=credits_100;user=42"}}
            """.trimIndent(),
            TelegramUpdate::class.java,
        )
        val successful = mapper.readValue(
            """
            {"update_id":2,"message":{"message_id":3,"from":{"id":42,"first_name":"A"},"chat":{"id":42},"successful_payment":{"currency":"XTR","total_amount":20,"invoice_payload":"pack=credits_100;user=42","telegram_payment_charge_id":"tg-charge","provider_payment_charge_id":""}}}
            """.trimIndent(),
            TelegramUpdate::class.java,
        )

        assertThat(preCheckout.preCheckoutQuery?.totalAmount).isEqualTo(20)
        assertThat(preCheckout.preCheckoutQuery?.from?.id).isEqualTo(42)
        assertThat(successful.message?.successfulPayment?.telegramPaymentChargeId).isEqualTo("tg-charge")
        assertThat(successful.message?.successfulPayment?.invoicePayload).isEqualTo("pack=credits_100;user=42")
    }
}
