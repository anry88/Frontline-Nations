package com.tggames.frontline.progression

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

data class ForceTier(
    val id: String,
    val nameKey: String,
    val minCp: Int,
    val maxCp: Int,
    val rewardPercent: Int,
)

@Component
class ForceTierCatalog(objectMapper: ObjectMapper) {
    val tiers: List<ForceTier> = ClassPathResource("catalog/force-tiers.json").inputStream.use {
        objectMapper.readValue(it, object : TypeReference<List<ForceTier>>() {})
    }.sortedBy(ForceTier::minCp).also(::validate)

    val minimumBattleCp: Int get() = tiers.first().minCp
    val maximumCapacity: Int get() = tiers.last().maxCp

    fun forDeployedCp(cp: Int): ForceTier = tiers.firstOrNull { cp in it.minCp..it.maxCp }
        ?: if (cp < minimumBattleCp) tiers.first() else error("No force tier for $cp CP")

    private fun validate(definitions: List<ForceTier>) {
        require(definitions.isNotEmpty()) { "Force tier catalog cannot be empty" }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Force tier ids must be unique" }
        require(definitions.first().minCp == 10) { "First force tier must start at 10 CP" }
        require(definitions.last().maxCp == 1_000) { "Final force tier must end at 1000 CP" }
        definitions.forEachIndexed { index, tier ->
            require(tier.id.matches(Regex("[a-z][a-z0-9-]*")))
            require(tier.minCp in 1..tier.maxCp)
            require(tier.maxCp <= 1_000)
            require(tier.rewardPercent >= 100)
            if (index > 0) {
                val previous = definitions[index - 1]
                require(tier.minCp == previous.maxCp + 1) { "Force tiers must be contiguous" }
            }
        }
    }

    companion object {
        fun capacityForLevel(level: Int): Int = (level.coerceAtLeast(1) + 9).coerceAtMost(1_000)
    }
}
