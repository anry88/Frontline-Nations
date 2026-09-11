package com.tggames.frontline.game

import com.tggames.frontline.i18n.GameLanguage
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class AllianceOption(val code: String, val name: String, val flag: String) {
    val label: String get() = "$flag $name"
}

object AllianceCatalog {
    val codes: List<String> = checkNotNull(javaClass.classLoader.getResourceAsStream("catalog/alliance-codes.txt"))
        .bufferedReader().useLines { lines ->
            lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        }.also {
            require(it.size == 250 && it.toSet().size == it.size) { "Invalid versioned alliance catalog" }
        }

    val all: Map<String, String> by lazy { codes.associateWith { name(it, GameLanguage.RU) } }

    fun contains(code: String) = code.uppercase() in codes

    fun option(code: String, language: GameLanguage) = AllianceOption(code.uppercase(), name(code, language), flag(code))

    fun name(code: String, language: GameLanguage = GameLanguage.RU): String {
        val normalized = code.uppercase()
        specialNames[normalized]?.get(language)?.let { return it }
        return Locale("", normalized).getDisplayCountry(language.locale)
            .takeUnless { it.isBlank() || it.equals(normalized, true) } ?: normalized
    }

    fun sorted(language: GameLanguage): List<AllianceOption> {
        val collator = Collator.getInstance(language.locale).apply { strength = Collator.PRIMARY }
        return codes.map { option(it, language) }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    fun recommended(language: GameLanguage) = recommendations.getValue(language).map { option(it, language) }

    fun search(query: String, language: GameLanguage, limit: Int = 10): List<AllianceOption> {
        val needle = searchable(query)
        if (needle.isBlank()) return emptyList()
        return codes.asSequence().map { code ->
            val option = option(code, language)
            val names = GameLanguage.entries.map { name(code, it) } + code
            val rank = when {
                code.equals(query, true) -> 0
                names.any { searchable(it) == needle } -> 1
                names.any { searchable(it).startsWith(needle) } -> 2
                names.any { searchable(it).contains(needle) } -> 3
                else -> 99
            }
            option to rank
        }.filter { it.second < 99 }
            .sortedWith(compareBy<Pair<AllianceOption, Int>> { it.second }.thenBy { it.first.name })
            .take(limit).map { it.first }.toList()
    }

    fun flag(code: String): String {
        val normalized = code.uppercase()
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) return "🌐"
        return normalized.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
    }

    private fun searchable(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val recommendations = mapOf(
        GameLanguage.EN to listOf("US", "GB", "IN", "CA", "AU"),
        GameLanguage.RU to listOf("RU", "UA", "BY", "KZ", "RS"),
        GameLanguage.ES to listOf("ES", "MX", "AR", "CO", "CL"),
        GameLanguage.PT to listOf("BR", "PT", "AO", "MZ", "CV"),
        GameLanguage.AR to listOf("SA", "EG", "AE", "MA", "DZ"),
        GameLanguage.ID to listOf("ID", "MY", "SG", "BN", "TL"),
        GameLanguage.HI to listOf("IN", "NP", "BD", "LK", "AE"),
        GameLanguage.TR to listOf("TR", "CY", "AZ", "DE", "BG"),
    )

    private val specialNames = mapOf(
        "XK" to mapOf(GameLanguage.EN to "Kosovo", GameLanguage.RU to "Косово", GameLanguage.ES to "Kosovo", GameLanguage.PT to "Kosovo", GameLanguage.AR to "كوسوفو", GameLanguage.ID to "Kosovo", GameLanguage.HI to "कोसोवो", GameLanguage.TR to "Kosova"),
        "PS" to mapOf(GameLanguage.EN to "Palestine", GameLanguage.RU to "Палестина", GameLanguage.ES to "Palestina", GameLanguage.PT to "Palestina", GameLanguage.AR to "فلسطين", GameLanguage.ID to "Palestina", GameLanguage.HI to "फ़िलिस्तीन", GameLanguage.TR to "Filistin"),
    )
}
