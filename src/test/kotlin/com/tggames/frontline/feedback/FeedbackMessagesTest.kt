package com.tggames.frontline.feedback

import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class FeedbackMessagesTest {
    @Test
    fun `every supported language has Telegram-safe feedback copy`() {
        GameLanguage.entries.forEach { language ->
            val messages = listOf(
                FeedbackMessages.inlineQuestion(language),
                FeedbackMessages.nudge(language, FeedbackNudgeTrigger.INACTIVE_BEFORE_FIRST),
                FeedbackMessages.nudge(language, FeedbackNudgeTrigger.INACTIVE_AFTER_FIRST),
                FeedbackMessages.commentPrompt(language),
                FeedbackMessages.thanks(language),
                FeedbackMessages.skipped(language),
                FeedbackMessages.expired(language),
            )
            assertThat(messages).allSatisfy { message ->
                assertThat(message).isNotBlank()
                assertThat(message.codePointCount(0, message.length)).isLessThanOrEqualTo(4096)
            }

            val buttons = FeedbackReason.entries.map { FeedbackMessages.reason(language, it) } + listOf(
                FeedbackMessages.inlineButton(language),
                FeedbackMessages.openButton(language),
                FeedbackMessages.skip(language),
                FeedbackMessages.commentButton(language),
            )
            assertThat(buttons).allSatisfy { button ->
                assertThat(button).isNotBlank()
                assertThat(button.codePointCount(0, button.length)).isLessThanOrEqualTo(44)
            }
        }
    }

    @Test
    fun `feedback callback payloads stay stable and within Telegram limit`() {
        val callbacks = FeedbackReason.entries.map { "feedback:reason:${it.value}" } + listOf(
            "feedback:open:inline", "feedback:open:nudge", "feedback:comment", "feedback:skip",
        )

        assertThat(FeedbackReason.entries.map { it.value }).doesNotHaveDuplicates()
        assertThat(callbacks).allSatisfy { callback ->
            assertThat(callback.toByteArray(StandardCharsets.UTF_8).size).isLessThanOrEqualTo(64)
        }
    }
}
