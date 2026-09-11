package com.tggames.frontline.progression

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CommandProgression(
    val commanderLevel: Int,
    val researchPoints: Long,
    val commandCapacity: Int,
    val currentTier: ForceTier,
    val nextExpansion: ForceTier?,
)

enum class CapacityExpansionStatus { SUCCESS, LEVEL_LOCKED, INSUFFICIENT_RESEARCH, MAXIMUM_REACHED, STALE }

data class CapacityExpansion(
    val status: CapacityExpansionStatus,
    val progression: CommandProgression,
    val expandedTo: Int? = null,
    val spentResearch: Int = 0,
)

@Service
class CommandProgressionService(
    private val jdbc: JdbcClient,
    private val tiers: ForceTierCatalog,
) {
    fun state(telegramId: Long): CommandProgression {
        val row = jdbc.sql(
            "SELECT commander_level, research_points, command_capacity FROM players WHERE telegram_id = :id",
        ).param("id", telegramId).query { rs, _ ->
            Triple(rs.getInt("commander_level"), rs.getLong("research_points"), rs.getInt("command_capacity"))
        }.single()
        return progression(row.first, row.second, row.third)
    }

    @Transactional
    fun expand(telegramId: Long, expectedCapacity: Int): CapacityExpansion {
        val row = jdbc.sql(
            "SELECT commander_level, research_points, command_capacity FROM players WHERE telegram_id = :id FOR UPDATE",
        ).param("id", telegramId).query { rs, _ ->
            Triple(rs.getInt("commander_level"), rs.getLong("research_points"), rs.getInt("command_capacity"))
        }.single()
        val before = progression(row.first, row.second, row.third)
        if (before.commandCapacity != expectedCapacity) return CapacityExpansion(CapacityExpansionStatus.STALE, before)
        expansionBlock(before)?.let { return CapacityExpansion(it, before) }
        val target = requireNotNull(before.nextExpansion)

        val transactionId = UUID.randomUUID()
        val updated = jdbc.sql(
            """
            UPDATE players
               SET research_points = research_points - :cost,
                   command_capacity = :capacity,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id
               AND command_capacity = :expected
               AND research_points >= :cost
            """.trimIndent(),
        ).param("cost", target.researchCost)
            .param("capacity", target.maxCp)
            .param("id", telegramId)
            .param("expected", expectedCapacity)
            .update()
        if (updated == 0) return CapacityExpansion(CapacityExpansionStatus.STALE, state(telegramId))

        jdbc.sql(
            """
            INSERT INTO command_capacity_upgrades(
                id, player_telegram_id, tier_id, capacity_before, capacity_after, research_cost
            ) VALUES (:id, :player, :tier, :before, :after, :cost)
            """.trimIndent(),
        ).param("id", transactionId)
            .param("player", telegramId)
            .param("tier", target.id)
            .param("before", before.commandCapacity)
            .param("after", target.maxCp)
            .param("cost", target.researchCost)
            .update()
        jdbc.sql(
            """
            INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id)
            VALUES (:player, 'RESEARCH_POINTS', :delta, 'COMMAND_CAPACITY', :reference)
            """.trimIndent(),
        ).param("player", telegramId)
            .param("delta", -target.researchCost.toLong())
            .param("reference", transactionId)
            .update()

        val after = progression(before.commanderLevel, before.researchPoints - target.researchCost, target.maxCp)
        return CapacityExpansion(CapacityExpansionStatus.SUCCESS, after, target.maxCp, target.researchCost)
    }

    internal fun progression(level: Int, research: Long, capacity: Int): CommandProgression = CommandProgression(
        commanderLevel = level,
        researchPoints = research,
        commandCapacity = capacity,
        currentTier = tiers.forCapacity(capacity),
        nextExpansion = tiers.nextExpansion(capacity),
    )

    internal fun expansionBlock(state: CommandProgression): CapacityExpansionStatus? {
        val target = state.nextExpansion ?: return CapacityExpansionStatus.MAXIMUM_REACHED
        if (state.commanderLevel < target.unlockLevel) return CapacityExpansionStatus.LEVEL_LOCKED
        if (state.researchPoints < target.researchCost) return CapacityExpansionStatus.INSUFFICIENT_RESEARCH
        return null
    }
}
