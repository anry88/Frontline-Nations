package com.tggames.frontline.inventory

import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.SpatialUnitResult
import com.tggames.frontline.battle.UnitBattleSnapshot
import com.tggames.frontline.campaign.WeeklyFormationResult
import com.tggames.frontline.campaign.WeeklyUnitContribution
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.catalog.EquipmentDefinition
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class OwnedUnit(
    val id: UUID,
    val code: String,
    val level: Int,
    val durability: Int,
    val origin: String,
    val reservedWeekKey: String? = null,
)

data class BattleGroup(
    val id: UUID,
    val presetNo: Int,
    val name: String,
    val active: Boolean,
    val version: Int,
    val units: List<OwnedUnit>,
)

data class Army(
    val commanderLevel: Int,
    val cpLimit: Int,
    val groups: List<BattleGroup>,
    val inventory: List<OwnedUnit>,
) {
    val activeGroup: BattleGroup get() = groups.first { it.active }
}

enum class EquipmentActionStatus { SUCCESS, LOCKED, RESERVED, NOT_FOUND, NO_AVAILABLE_UNIT, INSUFFICIENT_RESOURCES, MAX_LEVEL, GROUP_FULL, LAST_UNIT }

data class EquipmentCasualties(val survived: Int, val lost: Int)
data class WeeklyContributionCasualties(val contributionId: Long, val playerId: Long, val survived: Int, val lost: Int)

data class EquipmentAction(
    val status: EquipmentActionStatus,
    val unit: OwnedUnit? = null,
    val definition: EquipmentDefinition? = null,
    val quantity: Int = 1,
    val upgraded: Boolean = false,
)

@Service
class InventoryService(
    private val jdbc: JdbcClient,
    private val catalog: EquipmentCatalog,
) {
    @Transactional
    fun ensureStarter(telegramId: Long) {
        val granted = jdbc.sql(
            "INSERT INTO player_grants(player_telegram_id, grant_code) VALUES (:id, 'STARTER_ARMY_V1') ON CONFLICT DO NOTHING",
        ).param("id", telegramId).update()
        if (granted == 0) return

        val groups = (1..3).associateWith { preset -> UUID.randomUUID() }
        groups.forEach { (preset, id) ->
            jdbc.sql(
                "INSERT INTO battle_groups(id, player_telegram_id, preset_no, name, active) VALUES (:id, :player, :preset, :name, :active)",
            ).param("id", id)
                .param("player", telegramId)
                .param("preset", preset)
                .param("name", listOf("Alpha", "Bravo", "Charlie")[preset - 1])
                .param("active", preset == 1)
                .update()
        }

        listOf("MBT", "MBT", "ARTILLERY", "RECON_VEHICLE").forEachIndexed { index, code ->
            val unitId = UUID.randomUUID()
            jdbc.sql(
                "INSERT INTO player_units(id, player_telegram_id, unit_code, origin) VALUES (:id, :player, :code, 'STARTER')",
            ).param("id", unitId).param("player", telegramId).param("code", code).update()
            jdbc.sql(
                "INSERT INTO battle_group_units(group_id, player_unit_id, slot_no) VALUES (:groupId, :unitId, :slot)",
            ).param("groupId", groups.getValue(1)).param("unitId", unitId).param("slot", index + 1).update()
            recordEquipment(telegramId, unitId, "STARTER", 0, 0, null, 1)
        }
    }

    fun army(telegramId: Long): Army {
        val player = jdbc.sql("SELECT commander_level, command_capacity FROM players WHERE telegram_id = :id")
            .param("id", telegramId).query { rs, _ -> rs.getInt("commander_level") to rs.getInt("command_capacity") }.single()
        val units = jdbc.sql(
            "SELECT id, unit_code, level, durability, origin, reserved_week_key FROM player_units WHERE player_telegram_id = :id AND destroyed_at IS NULL ORDER BY created_at, id",
        ).param("id", telegramId).query { rs, _ ->
            OwnedUnit(rs.getObject("id", UUID::class.java), rs.getString("unit_code"), rs.getInt("level"), rs.getInt("durability"), rs.getString("origin"), rs.getString("reserved_week_key"))
        }.list()
        val membership = jdbc.sql(
            "SELECT group_id, player_unit_id FROM battle_group_units WHERE group_id IN (SELECT id FROM battle_groups WHERE player_telegram_id = :id) ORDER BY group_id, slot_no",
        ).param("id", telegramId).query { rs, _ ->
            rs.getObject("group_id", UUID::class.java) to rs.getObject("player_unit_id", UUID::class.java)
        }.list().groupBy({ it.first }, { it.second })
        val groups = jdbc.sql(
            "SELECT id, preset_no, name, active, version FROM battle_groups WHERE player_telegram_id = :id ORDER BY preset_no",
        ).param("id", telegramId).query { rs, _ ->
            val groupId = rs.getObject("id", UUID::class.java)
            BattleGroup(
                groupId,
                rs.getInt("preset_no"),
                rs.getString("name"),
                rs.getBoolean("active"),
                rs.getInt("version"),
                membership[groupId].orEmpty().mapNotNull { unitId -> units.firstOrNull { it.id == unitId } },
            )
        }.list()
        return Army(player.first, player.second, groups, units)
    }

    fun battleSnapshot(army: Army): CombatGroupSnapshot = battleSnapshot(army, army.activeGroup)

    fun battleSnapshot(army: Army, group: BattleGroup): CombatGroupSnapshot {
        return CombatGroupSnapshot(
            id = group.id,
            version = group.version,
            cpLimit = army.cpLimit,
            units = group.units.groupBy { it.code to it.level }.map { (key, ownedUnits) ->
                val (code, level) = key
                val quantity = ownedUnits.size
                val definition = catalog.require(code)
                val stats = definition.stats.scaled(level)
                UnitBattleSnapshot(
                    id = UUID.nameUUIDFromBytes("${group.id}:${group.version}:$code:$level".toByteArray(StandardCharsets.UTF_8)),
                    code = code,
                    level = level,
                    cpCost = definition.cpCost * quantity,
                    attack = stats.attack,
                    armor = stats.armor,
                    mobility = stats.mobility,
                    recon = stats.recon,
                    support = stats.support,
                    roles = definition.roles,
                    movementProfile = definition.spatial.movementProfile,
                    movementPoints = definition.spatial.movementPoints,
                    weaponRange = definition.spatial.weaponRange,
                    minimumRange = definition.spatial.minimumRange,
                    sightRange = definition.spatial.sightRange,
                    fireMode = definition.spatial.fireMode,
                    quantity = quantity,
                )
            }.sortedWith(compareBy<UnitBattleSnapshot> { it.code }.thenBy { it.level }),
        )
    }

    fun hasReservedUnits(group: BattleGroup): Boolean = group.units.any { it.reservedWeekKey != null }

    @Transactional
    fun grantDailyUnit(telegramId: Long, definition: EquipmentDefinition): OwnedUnit {
        val unitId = UUID.randomUUID()
        jdbc.sql(
            "INSERT INTO player_units(id, player_telegram_id, unit_code, origin) VALUES (:id, :player, :code, 'DAILY_REWARD')",
        ).param("id", unitId).param("player", telegramId).param("code", definition.code).update()
        recordEquipment(telegramId, unitId, "DAILY_REWARD", 0, 0, null, 1)
        return OwnedUnit(unitId, definition.code, 1, 100, "DAILY_REWARD")
    }

    @Transactional
    fun destroyPersonalCasualties(
        telegramId: Long,
        army: Army,
        snapshot: CombatGroupSnapshot,
        results: List<SpatialUnitResult>,
        battleId: UUID,
    ): EquipmentCasualties {
        val ownedByKey = army.activeGroup.units.groupBy { it.code to it.level }
        val snapshotById = snapshot.units.associateBy { it.id }
        val lostIds = results.flatMap { result ->
            val stack = snapshotById.getValue(result.id)
            val lost = (result.quantity - result.remainingQuantity).coerceAtLeast(0)
            ownedByKey[result.code to stack.level].orEmpty().sortedBy { it.id }.takeLast(lost).map { it.id }
        }.distinct()
        markDestroyed(telegramId, lostIds, "PERSONAL", "BATTLE_LOSS", battleId)
        return EquipmentCasualties(snapshot.units.sumOf { it.quantity } - lostIds.size, lostIds.size)
    }

    @Transactional
    fun reserveForWeeklyBattle(telegramId: Long, weekKey: String, unitIds: List<UUID>): Boolean =
        replaceWeeklyReservation(telegramId, weekKey, emptyList(), unitIds)

    @Transactional
    fun replaceWeeklyReservation(telegramId: Long, weekKey: String, previousUnitIds: List<UUID>, unitIds: List<UUID>): Boolean {
        if (unitIds.isEmpty()) return false
        val lockedIds = (previousUnitIds + unitIds).distinct()
        val rows = jdbc.sql(
            "SELECT id, reserved_week_key FROM player_units WHERE player_telegram_id = :player AND id IN (:ids) AND destroyed_at IS NULL FOR UPDATE",
        ).param("player", telegramId).param("ids", lockedIds)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("reserved_week_key") }.list()
        val rowsById = rows.toMap()
        if (unitIds.distinct().any { it !in rowsById }) return false
        if (unitIds.distinct().any { rowsById[it] != null && it !in previousUnitIds }) return false
        if (previousUnitIds.isNotEmpty()) {
            jdbc.sql("UPDATE player_units SET reserved_week_key = NULL, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player AND reserved_week_key = :week AND id IN (:ids)")
                .param("player", telegramId).param("week", weekKey).param("ids", previousUnitIds).update()
        }
        jdbc.sql("UPDATE player_units SET reserved_week_key = :week, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player AND id IN (:ids) AND destroyed_at IS NULL")
            .param("week", weekKey).param("player", telegramId).param("ids", unitIds).update()
        return true
    }

    @Transactional
    fun releaseWeeklyReservation(telegramId: Long, weekKey: String, unitIds: List<UUID>) {
        if (unitIds.isEmpty()) return
        jdbc.sql(
            "UPDATE player_units SET reserved_week_key = NULL, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player AND reserved_week_key = :week AND id IN (:ids)",
        ).param("player", telegramId).param("week", weekKey).param("ids", unitIds).update()
    }

    @Transactional
    fun settleWeeklyCasualties(
        weekKey: String,
        matchupId: UUID,
        allianceCode: String,
        formations: List<WeeklyFormationResult>,
        npcUnits: List<WeeklyUnitContribution>,
        seed: Long,
    ): List<WeeklyContributionCasualties> {
        val reserved = jdbc.sql(
            """
            SELECT unit.id, unit.player_telegram_id, unit.unit_code, unit.level, contribution.id AS contribution_id
              FROM player_units unit
              JOIN campaign_contributions contribution
                ON contribution.week_key = unit.reserved_week_key
               AND contribution.player_telegram_id = unit.player_telegram_id
               AND unit.id = ANY(contribution.unit_ids)
               AND contribution.contribution_type = 'EQUIPMENT_SNAPSHOT'
               AND contribution.voided_at IS NULL
             WHERE unit.reserved_week_key = :week AND contribution.alliance_code = :alliance
               AND unit.destroyed_at IS NULL
             FOR UPDATE OF unit
            """.trimIndent(),
        ).param("week", weekKey).param("alliance", allianceCode)
            .query { rs, _ -> ReservedUnit(rs.getObject("id", UUID::class.java), rs.getLong("player_telegram_id"), rs.getString("unit_code"), rs.getInt("level"), rs.getLong("contribution_id")) }
            .list()
        val lostIds = mutableSetOf<UUID>()
        formations.forEach { formation ->
            val key = formation.unitCode to formation.level
            val perUnitPower = (formation.initialPower / formation.quantity.coerceAtLeast(1)).coerceAtLeast(1)
            val survivingCount = ((formation.remainingPower + perUnitPower - 1) / perUnitPower).toInt().coerceIn(0, formation.quantity)
            val casualtyCount = formation.quantity - survivingCount
            val realTokens = reserved.filter {
                it.playerId == formation.contributorPlayerId &&
                    (formation.sourceContributionId == null || it.contributionId == formation.sourceContributionId) &&
                    it.code == key.first && it.level == key.second
            }
                .map { CasualtyToken(it.id.toString(), it.id) }
            val npcCount = if (formation.contributorPlayerId == null) {
                npcUnits.filter { it.code == key.first && it.level == key.second }.sumOf { it.quantity }
            } else {
                0
            }
            val npcTokens = (0 until npcCount).map { CasualtyToken("npc:${key.first}:${key.second}:$it", null) }
            require(realTokens.size + npcTokens.size == formation.quantity) {
                "Weekly formation ${key.first}/L${key.second}/${formation.contributorPlayerId ?: "NPC"} does not match reserved and NPC units"
            }
            (realTokens + npcTokens).sortedBy { casualtyOrder(seed, key, it.key) }.take(casualtyCount)
                .mapNotNullTo(lostIds) { it.ownedId }
        }
        markDestroyed(0, lostIds.toList(), "WEEKLY", "CAMPAIGN_LOSS", matchupId)
        jdbc.sql("UPDATE player_units SET reserved_week_key = NULL, updated_at = CURRENT_TIMESTAMP WHERE reserved_week_key = :week AND player_telegram_id IN (SELECT telegram_id FROM players WHERE alliance_code = :alliance)")
            .param("week", weekKey).param("alliance", allianceCode).update()
        return reserved.groupBy { it.contributionId }.map { (contributionId, units) ->
            val lost = units.count { it.id in lostIds }
            WeeklyContributionCasualties(contributionId, units.first().playerId, units.size - lost, lost)
        }
    }

    @Transactional
    fun acquire(telegramId: Long, code: String, quantity: Int = 1): EquipmentAction {
        val definition = catalog.get(code) ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        if (quantity !in 1..MAX_PURCHASE_QUANTITY) return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        val commanderLevel = jdbc.sql("SELECT commander_level FROM players WHERE telegram_id = :id FOR UPDATE")
            .param("id", telegramId).query(Int::class.java).single()
        if (commanderLevel < definition.unlockLevel) return EquipmentAction(EquipmentActionStatus.LOCKED, definition = definition)
        val credits = definition.buyCredits * quantity
        val charged = jdbc.sql(
            """
            UPDATE players
               SET credits = credits - :credits, updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id AND credits >= :credits
            """.trimIndent(),
        ).param("credits", credits).param("id", telegramId).update()
        if (charged == 0) return EquipmentAction(EquipmentActionStatus.INSUFFICIENT_RESOURCES, definition = definition)

        val batchId = UUID.randomUUID()
        val origin = "PURCHASE"
        val unitIds = (1..quantity).map {
            val unitId = UUID.randomUUID()
            jdbc.sql(
                "INSERT INTO player_units(id, player_telegram_id, unit_code, origin) VALUES (:unitId, :player, :code, :origin)",
            ).param("unitId", unitId).param("player", telegramId).param("code", definition.code).param("origin", origin).update()
            recordEquipment(
                telegramId,
                unitId,
                origin,
                -(credits / quantity).toLong(),
                0,
                null,
                1,
            )
            unitId
        }
        recordWallet(telegramId, "CREDITS", -credits.toLong(), origin, batchId)
        return EquipmentAction(
            EquipmentActionStatus.SUCCESS,
            OwnedUnit(unitIds.first(), definition.code, 1, 100, origin),
            definition,
            quantity,
        )
    }

    @Transactional
    fun upgrade(telegramId: Long, unitId: UUID): EquipmentAction {
        val unit = findOwnedUnit(telegramId, unitId, lock = true) ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        val definition = catalog.require(unit.code)
        if (unit.reservedWeekKey != null) return EquipmentAction(EquipmentActionStatus.RESERVED, unit, definition)
        if (unit.level >= 5) return EquipmentAction(EquipmentActionStatus.MAX_LEVEL, unit, definition)
        val credits = definition.upgradeCredits(unit.level)
        val materials = definition.upgradeMaterials(unit.level)
        val charged = jdbc.sql(
            "UPDATE players SET credits = credits - :credits, materials = materials - :materials, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND credits >= :credits AND materials >= :materials",
        ).param("credits", credits).param("materials", materials).param("id", telegramId).update()
        if (charged == 0) return EquipmentAction(EquipmentActionStatus.INSUFFICIENT_RESOURCES, unit, definition)
        val next = unit.copy(level = unit.level + 1)
        jdbc.sql("UPDATE player_units SET level = :level, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("level", next.level).param("id", unit.id).update()
        recordWallet(telegramId, "CREDITS", -credits.toLong(), "UPGRADE", unit.id)
        recordWallet(telegramId, "MATERIALS", -materials.toLong(), "UPGRADE", unit.id)
        recordEquipment(telegramId, unit.id, "UPGRADE", -credits.toLong(), -materials.toLong(), unit.level, next.level)
        bumpGroupsContaining(listOf(unit.id))
        return EquipmentAction(EquipmentActionStatus.SUCCESS, next, definition, upgraded = true)
    }

    @Transactional
    fun upgradeBatch(telegramId: Long, code: String, level: Int, requestedQuantity: Int): EquipmentAction {
        val definition = catalog.get(code) ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        if (level !in 1..4 || requestedQuantity !in 1..MAX_PURCHASE_QUANTITY) {
            return EquipmentAction(EquipmentActionStatus.NOT_FOUND, definition = definition)
        }
        val units = jdbc.sql(
            """
            SELECT id, unit_code, level, durability, origin, reserved_week_key
              FROM player_units
             WHERE player_telegram_id = :player AND unit_code = :code AND level = :level
               AND destroyed_at IS NULL AND reserved_week_key IS NULL
             ORDER BY id
             LIMIT :quantity
             FOR UPDATE
            """.trimIndent(),
        ).param("player", telegramId)
            .param("code", definition.code)
            .param("level", level)
            .param("quantity", requestedQuantity)
            .query { rs, _ ->
                OwnedUnit(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("unit_code"),
                    rs.getInt("level"),
                    rs.getInt("durability"),
                    rs.getString("origin"),
                    rs.getString("reserved_week_key"),
                )
            }.list()
        if (units.isEmpty()) return EquipmentAction(EquipmentActionStatus.NO_AVAILABLE_UNIT, definition = definition)

        val creditsPerUnit = definition.upgradeCredits(level)
        val materialsPerUnit = definition.upgradeMaterials(level)
        val credits = creditsPerUnit * units.size
        val materials = materialsPerUnit * units.size
        val charged = jdbc.sql(
            "UPDATE players SET credits = credits - :credits, materials = materials - :materials, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND credits >= :credits AND materials >= :materials",
        ).param("credits", credits).param("materials", materials).param("id", telegramId).update()
        if (charged == 0) return EquipmentAction(EquipmentActionStatus.INSUFFICIENT_RESOURCES, definition = definition)

        val batchId = UUID.randomUUID()
        units.forEach { unit ->
            jdbc.sql("UPDATE player_units SET level = :level, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("level", level + 1).param("id", unit.id).update()
            recordEquipment(
                telegramId,
                unit.id,
                "UPGRADE",
                -creditsPerUnit.toLong(),
                -materialsPerUnit.toLong(),
                level,
                level + 1,
            )
        }
        recordWallet(telegramId, "CREDITS", -credits.toLong(), "UPGRADE", batchId)
        recordWallet(telegramId, "MATERIALS", -materials.toLong(), "UPGRADE", batchId)
        bumpGroupsContaining(units.map { it.id })
        return EquipmentAction(
            EquipmentActionStatus.SUCCESS,
            units.first().copy(level = level + 1),
            definition,
            units.size,
            upgraded = true,
        )
    }

    @Transactional
    fun toggleInActiveGroup(telegramId: Long, unitId: UUID): EquipmentAction {
        jdbc.sql("SELECT id FROM battle_groups WHERE player_telegram_id = :player AND active FOR UPDATE")
            .param("player", telegramId).query(UUID::class.java).single()
        val army = army(telegramId)
        val group = army.activeGroup
        val unit = army.inventory.firstOrNull { it.id == unitId } ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        val definition = catalog.require(unit.code)
        val selected = group.units.any { it.id == unitId }
        if (unit.reservedWeekKey != null) return EquipmentAction(EquipmentActionStatus.RESERVED, unit, definition)
        if (selected && group.units.size == 1) return EquipmentAction(EquipmentActionStatus.LAST_UNIT, unit, definition)
        if (!selected) {
            val currentCp = group.units.sumOf { catalog.require(it.code).cpCost }
            if (currentCp + definition.cpCost > army.cpLimit) return EquipmentAction(EquipmentActionStatus.GROUP_FULL, unit, definition)
            val slot = nextSlot(group.id)
            jdbc.sql("INSERT INTO battle_group_units(group_id, player_unit_id, slot_no) VALUES (:groupId, :unitId, :slot)")
                .param("groupId", group.id).param("unitId", unitId).param("slot", slot).update()
        } else {
            jdbc.sql("DELETE FROM battle_group_units WHERE group_id = :groupId AND player_unit_id = :unitId")
                .param("groupId", group.id).param("unitId", unitId).update()
        }
        jdbc.sql("UPDATE battle_groups SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", group.id).update()
        return EquipmentAction(EquipmentActionStatus.SUCCESS, unit, definition)
    }

    @Transactional
    fun addUnitTypeToActiveGroup(telegramId: Long, code: String): EquipmentAction {
        val definition = catalog.get(code) ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        lockActiveGroup(telegramId)
        val army = army(telegramId)
        val group = army.activeGroup
        val selectedIds = group.units.map { it.id }.toSet()
        val unit = army.inventory.filter { it.code == definition.code && it.id !in selectedIds && it.reservedWeekKey == null }
            .maxWithOrNull(compareBy<OwnedUnit> { it.level }.thenBy { it.id })
            ?: return EquipmentAction(EquipmentActionStatus.NO_AVAILABLE_UNIT, definition = definition)
        val currentCp = group.units.sumOf { catalog.require(it.code).cpCost }
        if (currentCp + definition.cpCost > army.cpLimit) return EquipmentAction(EquipmentActionStatus.GROUP_FULL, unit, definition)
        jdbc.sql("INSERT INTO battle_group_units(group_id, player_unit_id, slot_no) VALUES (:groupId, :unitId, :slot)")
            .param("groupId", group.id).param("unitId", unit.id).param("slot", nextSlot(group.id)).update()
        bumpGroupVersion(group.id)
        return EquipmentAction(EquipmentActionStatus.SUCCESS, unit, definition)
    }

    @Transactional
    fun removeUnitTypeFromActiveGroup(telegramId: Long, code: String): EquipmentAction {
        val definition = catalog.get(code) ?: return EquipmentAction(EquipmentActionStatus.NOT_FOUND)
        lockActiveGroup(telegramId)
        val army = army(telegramId)
        val group = army.activeGroup
        val matching = group.units.filter { it.code == definition.code }
        val unit = matching.filter { it.reservedWeekKey == null }
            .minWithOrNull(compareBy<OwnedUnit> { it.level }.thenBy { it.id })
            ?: return if (matching.any { it.reservedWeekKey != null }) {
                EquipmentAction(EquipmentActionStatus.RESERVED, matching.first(), definition)
            } else {
                EquipmentAction(EquipmentActionStatus.NO_AVAILABLE_UNIT, definition = definition)
            }
        if (group.units.size == 1) return EquipmentAction(EquipmentActionStatus.LAST_UNIT, unit, definition)
        jdbc.sql("DELETE FROM battle_group_units WHERE group_id = :groupId AND player_unit_id = :unitId")
            .param("groupId", group.id).param("unitId", unit.id).update()
        bumpGroupVersion(group.id)
        return EquipmentAction(EquipmentActionStatus.SUCCESS, unit, definition)
    }

    @Transactional
    fun activatePreset(telegramId: Long, presetNo: Int): Boolean {
        if (presetNo !in 1..3) return false
        val target = jdbc.sql("SELECT id FROM battle_groups WHERE player_telegram_id = :player AND preset_no = :preset")
            .param("player", telegramId).param("preset", presetNo).query(UUID::class.java).optional().orElse(null) ?: return false
        jdbc.sql("UPDATE battle_groups SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player AND active")
            .param("player", telegramId).update()
        jdbc.sql("UPDATE battle_groups SET active = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", target).update()
        return true
    }

    private fun findOwnedUnit(telegramId: Long, unitId: UUID, lock: Boolean): OwnedUnit? = jdbc.sql(
        "SELECT id, unit_code, level, durability, origin, reserved_week_key FROM player_units WHERE player_telegram_id = :player AND id = :id AND destroyed_at IS NULL${if (lock) " FOR UPDATE" else ""}",
    ).param("player", telegramId).param("id", unitId).query { rs, _ ->
        OwnedUnit(rs.getObject("id", UUID::class.java), rs.getString("unit_code"), rs.getInt("level"), rs.getInt("durability"), rs.getString("origin"), rs.getString("reserved_week_key"))
    }.optional().orElse(null)

    private fun lockActiveGroup(telegramId: Long) {
        jdbc.sql("SELECT id FROM battle_groups WHERE player_telegram_id = :player AND active FOR UPDATE")
            .param("player", telegramId).query(UUID::class.java).single()
    }

    private fun nextSlot(groupId: UUID): Int = jdbc.sql(
        """
        SELECT slots.candidate
          FROM generate_series(1, :maximum) AS slots(candidate)
         WHERE NOT EXISTS (
             SELECT 1
               FROM battle_group_units
              WHERE group_id = :groupId
                AND slot_no = slots.candidate
         )
         ORDER BY slots.candidate
         LIMIT 1
        """.trimIndent(),
    ).param("maximum", MAX_GROUP_SLOTS).param("groupId", groupId).query(Int::class.java).single()

    private fun bumpGroupVersion(groupId: UUID) {
        jdbc.sql("UPDATE battle_groups SET version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", groupId).update()
    }

    private fun bumpGroupsContaining(unitIds: List<UUID>) {
        unitIds.flatMap { unitId ->
            jdbc.sql("SELECT group_id FROM battle_group_units WHERE player_unit_id = :unitId")
                .param("unitId", unitId)
                .query(UUID::class.java)
                .list()
        }.distinct().forEach(::bumpGroupVersion)
    }

    private fun markDestroyed(telegramId: Long, unitIds: List<UUID>, type: String, action: String, referenceId: UUID) {
        if (unitIds.isEmpty()) return
        bumpGroupsContaining(unitIds)
        val units = jdbc.sql("SELECT id, player_telegram_id, level FROM player_units WHERE id IN (:ids) AND destroyed_at IS NULL FOR UPDATE")
            .param("ids", unitIds)
            .query { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getLong("player_telegram_id"), rs.getInt("level")) }
            .list()
        require(units.size == unitIds.distinct().size) { "Equipment casualty set changed during resolution" }
        units.forEach { (id, owner, level) ->
            if (telegramId != 0L) require(owner == telegramId)
            jdbc.sql("UPDATE player_units SET destroyed_at = CURRENT_TIMESTAMP, destroyed_in_type = :type, destroyed_reference_id = :reference, reserved_week_key = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND destroyed_at IS NULL")
                .param("type", type).param("reference", referenceId).param("id", id).update()
            recordEquipment(owner, id, action, 0, 0, level, level)
        }
    }

    private fun casualtyOrder(seed: Long, key: Pair<String, Int>, token: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$seed:${key.first}:${key.second}:$token".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun recordWallet(telegramId: Long, resource: String, delta: Long, reason: String, referenceId: UUID) {
        jdbc.sql(
            "INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id) VALUES (:player, :resource, :delta, :reason, :reference)",
        ).param("player", telegramId).param("resource", resource).param("delta", delta).param("reason", reason).param("reference", referenceId).update()
    }

    private fun recordEquipment(
        telegramId: Long,
        unitId: UUID,
        action: String,
        credits: Long,
        materials: Long,
        levelBefore: Int?,
        levelAfter: Int,
    ) {
        jdbc.sql(
            """
            INSERT INTO equipment_transactions(id, player_telegram_id, player_unit_id, action, credits_delta, materials_delta, level_before, level_after)
            VALUES (:id, :player, :unit, :action, :credits, :materials, :before, :after)
            """.trimIndent(),
        ).param("id", UUID.randomUUID()).param("player", telegramId).param("unit", unitId).param("action", action)
            .param("credits", credits).param("materials", materials).param("before", levelBefore).param("after", levelAfter).update()
    }

    companion object {
        const val MAX_GROUP_SLOTS = 1_000
        const val MAX_PURCHASE_QUANTITY = 25
    }

    private data class ReservedUnit(val id: UUID, val playerId: Long, val code: String, val level: Int, val contributionId: Long)
    private data class CasualtyToken(val key: String, val ownedId: UUID?)
}
