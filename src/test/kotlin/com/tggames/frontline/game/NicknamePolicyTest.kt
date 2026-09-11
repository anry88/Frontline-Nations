package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NicknamePolicyTest {
    private val policy = NicknamePolicy()

    @Test
    fun `normalizes whitespace removes bidi controls and caps code points`() {
        val raw = "\u202E  Commander   ${"X".repeat(40)}  "
        val result = policy.validate(raw) as NicknameValidation.Valid

        assertThat(result.value).doesNotContain("\u202E").doesNotContain("  ")
        assertThat(result.value.codePointCount(0, result.value.length)).isEqualTo(30)
    }

    @Test
    fun `masks profanity using RiverKing policy`() {
        val result = policy.validate("Captain блять") as NicknameValidation.Valid

        assertThat(result.value).isEqualTo("Captain *****").doesNotContain("бля")
    }

    @Test
    fun `rejects content without letters or numbers`() {
        assertThat(policy.validate("  ⚔️ ---  ")).isEqualTo(NicknameValidation.Invalid)
    }
}
