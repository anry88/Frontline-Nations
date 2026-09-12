package com.tggames.frontline.catalog

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.i18n.GameLanguage
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

data class UnitStats(
    val attack: Int,
    val armor: Int,
    val mobility: Int,
    val recon: Int,
    val support: Int,
) {
    fun scaled(level: Int): UnitStats {
        val percent = 100 + (level.coerceIn(1, 5) - 1) * 12
        fun scale(value: Int) = value * percent / 100
        return UnitStats(scale(attack), scale(armor), scale(mobility), scale(recon), scale(support))
    }
}

enum class MovementProfile { TRACKED, WHEELED, AIR }

enum class FireMode { DIRECT, INDIRECT, AIR_TO_GROUND, AIR_INTERCEPT, AIR_DEFENSE }

data class SpatialProfile(
    val movementProfile: MovementProfile,
    val movementPoints: Int,
    val weaponRange: Int,
    val minimumRange: Int = 1,
    val sightRange: Int,
    val fireMode: FireMode,
)

data class EquipmentDefinition(
    val code: String,
    val emoji: String,
    val iconPath: String,
    val cpCost: Int,
    val unlockLevel: Int,
    val buyCredits: Int,
    val upgradeCredits: Int,
    val upgradeMaterials: Int,
    val stats: UnitStats,
    val spatial: SpatialProfile,
    val roles: Set<String>,
    val names: Map<String, String>,
) {
    fun name(language: GameLanguage): String = names[language.code] ?: names.getValue("en")
    fun upgradeCredits(fromLevel: Int): Int = upgradeCredits * fromLevel
    fun upgradeMaterials(fromLevel: Int): Int = upgradeMaterials * fromLevel
}

@Component
class EquipmentCatalog(objectMapper: ObjectMapper) {
    val units: List<EquipmentDefinition> = ClassPathResource("catalog/equipment-catalog.json").inputStream.use {
        objectMapper.readValue(it, object : TypeReference<List<EquipmentDefinition>>() {})
    }.also(::validate)

    private val byCode = units.associateBy { it.code }

    fun get(code: String): EquipmentDefinition? = byCode[code.uppercase()]
    fun require(code: String): EquipmentDefinition = requireNotNull(get(code)) { "Unknown unit code: $code" }

    private fun validate(definitions: List<EquipmentDefinition>) {
        require(definitions.isNotEmpty()) { "Equipment catalog cannot be empty" }
        require(definitions.map { it.code }.distinct().size == definitions.size) { "Equipment codes must be unique" }
        definitions.forEach {
            require(it.cpCost in 1..5 && it.unlockLevel in 1..50)
            require(it.buyCredits > 0 && it.upgradeCredits > 0 && it.upgradeMaterials > 0)
            require(it.spatial.movementPoints in 1..4)
            require(it.spatial.weaponRange in 1..6)
            require(it.spatial.minimumRange in 1..it.spatial.weaponRange)
            require(it.spatial.sightRange in 1..6)
            require(GameLanguage.entries.all { language -> it.names.containsKey(language.code) }) {
                "${it.code} is missing a supported translation"
            }
        }
    }
}
