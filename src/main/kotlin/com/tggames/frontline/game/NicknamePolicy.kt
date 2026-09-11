package com.tggames.frontline.game

import org.springframework.stereotype.Component
import java.text.Normalizer

sealed interface NicknameValidation {
    data class Valid(val value: String) : NicknameValidation
    data object Invalid : NicknameValidation
}

@Component
class NicknamePolicy {
    private val bidiAndControls = Regex("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]|[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val whitespace = Regex("\\s+")
    private val profanity: List<Regex> by lazy {
        javaClass.classLoader.getResourceAsStream("profanity.txt")
            ?.bufferedReader()
            ?.use { reader ->
                reader.readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .sortedByDescending(String::length)
                    .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
            }.orEmpty()
    }

    fun validate(raw: String): NicknameValidation {
        var value = Normalizer.normalize(raw, Normalizer.Form.NFC)
        value = bidiAndControls.replace(value, "")
        value = whitespace.replace(value, " ").trim()
        profanity.forEach { pattern ->
            value = pattern.replace(value) { "*".repeat(it.value.codePointCount(0, it.value.length)) }
        }
        value = value.codePoints().limit(MAX_CODE_POINTS.toLong()).toArray()
            .let { String(it, 0, it.size) }
            .trim()
        return if (value.any(Char::isLetterOrDigit)) NicknameValidation.Valid(value) else NicknameValidation.Invalid
    }

    companion object {
        const val MAX_CODE_POINTS = 30
    }
}
