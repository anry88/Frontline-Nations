package com.tggames.frontline.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.BattleEngine
import com.tggames.frontline.battle.DeploymentPlan
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.battle.SpatialEndReason
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.campaign.CampaignService
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.inventory.Army
import com.tggames.frontline.inventory.EquipmentAction
import com.tggames.frontline.inventory.EquipmentActionStatus
import com.tggames.frontline.inventory.InventoryService
import com.tggames.frontline.inventory.OwnedUnit
import com.tggames.frontline.progression.ForceTier
import com.tggames.frontline.progression.ForceTierCatalog
import com.tggames.frontline.progression.CommanderProgression
import com.tggames.frontline.replay.ReplayService
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
import com.tggames.frontline.telegram.TelegramCallbackQuery
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramUpdate
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Service
class GameService(
    private val jdbc: JdbcClient,
    private val telegram: TelegramClient,
    private val battleEngine: BattleEngine,
    private val properties: FrontlineProperties,
    private val objectMapper: ObjectMapper,
    private val campaigns: CampaignService,
    private val nicknamePolicy: NicknamePolicy,
    private val inventory: InventoryService,
    private val equipment: EquipmentCatalog,
    private val forceTiers: ForceTierCatalog,
    private val replays: ReplayService,
    private val rankings: RankingService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(update: TelegramUpdate) {
        if (!claimUpdate(update.updateId)) return

        update.callbackQuery?.let { callback ->
            ensurePlayer(callback.from.id, callback.from.firstName, callback.from.languageCode)
            val replayCallback = callback.data.orEmpty().startsWith("replay:")
            if (replayCallback) telegram.answerCallback(callback.id)
            handleCallback(callback)
            if (!replayCallback) telegram.answerCallback(callback.id)
            return
        }

        val message = update.message ?: return
        val user = message.from ?: return
        ensurePlayer(user.id, user.firstName, user.languageCode)
        val command = message.text.orEmpty().trim().substringBefore('@').substringBefore(' ').lowercase()
        val argument = message.text.orEmpty().trim().substringAfter(' ', "").trim()

        when (command) {
            "/start" -> start(user.id, user.firstName, message.chat.id)
            "/battle" -> battleMenu(user.id, user.firstName, message.chat.id)
            "/army", "/hangar" -> armyMenu(user.id, message.chat.id)
            "/shop" -> shopMenu(user.id, message.chat.id)
            "/upgrade" -> upgradeMenu(user.id, message.chat.id)
            "/daily" -> claimDailyReward(user.id, message.chat.id)
            "/profile" -> telegram.sendMessage(message.chat.id, profileText(user.id), actionKeyboard(user.id, language(user.id)))
            "/front" -> front(user.id, user.firstName, message.chat.id)
            "/contribute" -> contribute(user.id, user.firstName, message.chat.id)
            "/rankings", "/rating", "/ratings" -> rankingMenu(user.id, message.chat.id)
            "/guide" -> guide(user.id, message.chat.id, 0)
            "/language" -> if (argument.isBlank()) languageMenu(user.id, message.chat.id) else selectLanguage(user.id, message.chat.id, argument.lowercase())
            "/nickname" -> nickname(user.id, message.chat.id, argument)
            "/country" -> countryMenu(user.id, message.chat.id, argument)
            "/settings" -> settings(user.id, message.chat.id)
            "/help", "" -> telegram.sendMessage(message.chat.id, helpText(language(user.id)), actionKeyboard(user.id, language(user.id)))
            else -> telegram.sendMessage(message.chat.id, "${GameI18n.t(language(user.id), "unknown")}\n\n${helpText(language(user.id))}", actionKeyboard(user.id, language(user.id)))
        }
    }

    private fun handleCallback(callback: TelegramCallbackQuery) {
        val chatId = callback.message?.chat?.id ?: callback.from.id
        val data = callback.data.orEmpty()
        val language = language(callback.from.id)
        when {
            data.startsWith("country:select:") -> {
                selectAlliance(callback.from.id, data.substringAfterLast(':'), chatId)
            }
            data.startsWith("country:page:") -> countryPage(callback.from.id, chatId, data.substringAfterLast(':').toIntOrNull() ?: 0)
            data == "country:all" -> countryPage(callback.from.id, chatId, 0)
            data.startsWith("language:") -> selectLanguage(callback.from.id, chatId, data.substringAfterLast(':'))
            data == "nickname:confirm" -> confirmNickname(callback.from.id, chatId)
            data == "nickname:cancel" -> cancelNickname(callback.from.id, chatId)
            data == "nav:battle" -> battleMenu(callback.from.id, callback.from.firstName, chatId)
            data == "nav:army" -> armyMenu(callback.from.id, chatId)
            data == "nav:shop" -> shopMenu(callback.from.id, chatId)
            data == "nav:upgrade" -> upgradeMenu(callback.from.id, chatId)
            data == "nav:daily" -> claimDailyReward(callback.from.id, chatId)
            data == "nav:profile" -> telegram.sendMessage(chatId, profileText(callback.from.id), actionKeyboard(callback.from.id, language))
            data == "nav:front" -> front(callback.from.id, callback.from.firstName, chatId)
            data == "nav:rankings" -> rankingMenu(callback.from.id, chatId)
            data == "nav:guide" -> guide(callback.from.id, chatId, 0)
            data.startsWith("rank:alliances:") -> allianceRanking(callback.from.id, chatId, data.substringAfterLast(':').toIntOrNull() ?: 0)
            data == "rank:players" -> playerRanking(callback.from.id, chatId)
            data.startsWith("guide:") -> guide(callback.from.id, chatId, data.substringAfterLast(':').toIntOrNull() ?: 0)
            data == "front:manage" -> contribute(callback.from.id, callback.from.firstName, chatId)
            data.startsWith("front:send:") -> frontEntries(callback.from.id, chatId, data.removePrefix("front:send:"))
            data.startsWith("front:entry:") -> frontTactics(callback.from.id, chatId, data.removePrefix("front:entry:"))
            data.startsWith("front:commit:") -> commitFrontGroup(callback.from.id, chatId, data.removePrefix("front:commit:"))
            data.startsWith("front:withdraw:") -> withdrawFrontGroup(callback.from.id, chatId, data.substringAfterLast(':').toLongOrNull())
            data == "nav:settings" -> settings(callback.from.id, chatId)
            data.startsWith("op:") -> showDeployment(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("deploy:") -> showObjectives(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("objective:") -> showTactics(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("fight:") -> resolveBattle(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("replay:b:") -> sendPersonalReplay(callback.from.id, chatId, data.removePrefix("replay:b:"))
            data.startsWith("replay:w:") -> sendWeeklyReplay(callback.from.id, chatId, data.removePrefix("replay:w:"))
            data.startsWith("shop:view:") -> shopDetails(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("shop:buy:") -> acquireUnit(callback.from.id, chatId, data.removePrefix("shop:buy:"), false)
            data.startsWith("shop:craft:") -> acquireUnit(callback.from.id, chatId, data.removePrefix("shop:craft:"), true)
            data.startsWith("unit:upgrade-batch:") -> upgradeBatch(callback.from.id, chatId, data.removePrefix("unit:upgrade-batch:"))
            data.startsWith("unit:upgrade:") -> upgradeUnit(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:toggle:") -> toggleUnit(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:add:") -> addUnitType(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:remove:") -> removeUnitType(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:preset:") -> activatePreset(callback.from.id, chatId, data.substringAfterLast(':'))
            else -> telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(callback.from.id, language))
        }
    }

    private fun claimUpdate(updateId: Long): Boolean = try {
        jdbc.sql("INSERT INTO telegram_updates(update_id) VALUES (:id)")
            .param("id", updateId)
            .update()
        true
    } catch (_: DuplicateKeyException) {
        false
    }

    private fun start(telegramId: Long, firstName: String, chatId: Long) {
        val current = player(telegramId)
        val language = GameLanguage.fromStored(current.language)
        if (current.allianceCode != null) {
            telegram.sendMessage(chatId, "${GameI18n.t(language, "welcome_back", current.displayName)}\n\n${profileText(telegramId)}", actionKeyboard(telegramId, language))
            return
        }
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "welcome", firstName)}\n\n${GameI18n.t(language, "choose_country")}",
            recommendedCountryKeyboard(language, GameLanguage.fromTelegram(current.telegramLanguage)),
        )
    }

    private fun selectAlliance(telegramId: Long, code: String, chatId: Long) {
        val language = language(telegramId)
        if (!AllianceCatalog.contains(code)) return
        val updated = jdbc.sql("UPDATE players SET alliance_code = :code, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND alliance_code IS NULL")
            .param("code", code)
            .param("id", telegramId)
            .update()
        val selected = AllianceCatalog.option(code, language).label
        if (updated == 0) {
            val current = player(telegramId).allianceCode?.let { AllianceCatalog.option(it, language).label } ?: selected
            telegram.sendMessage(chatId, GameI18n.t(language, "country_locked", current), actionKeyboard(telegramId, language))
        } else {
            campaigns.ensureCurrentWeek()
            telegram.sendMessage(chatId, "${GameI18n.t(language, "country_selected", selected)}\n\n${profileText(telegramId)}", actionKeyboard(telegramId, language))
        }
    }

    private fun battleMenu(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, GameI18n.t(language, "choose_country"), recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            return
        }
        val army = inventory.army(telegramId)
        if (army.activeGroup.units.isEmpty()) {
            telegram.sendMessage(chatId, GameI18n.t(language, "army_empty"), armyKeyboard(army, language))
            return
        }
        if (inventory.hasReservedUnits(army.activeGroup)) {
            telegram.sendMessage(chatId, GameI18n.t(language, "weekly_units_reserved"), armyKeyboard(army, language))
            return
        }
        val deployedCp = groupCp(army)
        if (deployedCp < forceTiers.minimumBattleCp) {
            telegram.sendMessage(
                chatId,
                GameI18n.t(language, "group_below_minimum", deployedCp, forceTiers.minimumBattleCp),
                armyKeyboard(army, language),
            )
            return
        }
        val forceTier = forceTiers.forDeployedCp(deployedCp)
        val offerVersion = jdbc.sql(
            "UPDATE players SET battle_offer_version = battle_offer_version + 1, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id RETURNING battle_offer_version",
        ).param("id", telegramId).query(Long::class.java).single()

        val offers = offersFor(telegramId, offerVersion)
        val text = buildString {
            appendLine(GameI18n.t(language, "operations"))
            appendLine("${GameI18n.t(language, "active_group")}: ${army.activeGroup.name} · ${groupCp(army)}/${army.cpLimit} CP")
            appendLine("${GameI18n.t(language, "battle_category")}: ${forceTierLabel(language, forceTier)}")
            appendLine()
            offers.forEachIndexed { index, offer ->
                val rewardPercent = offer.difficulty.rewardPercent * forceTier.rewardPercent / 100
                appendLine("${index + 1}. ${offer.difficulty.icon} ${GameI18n.battlefield(language, offer.battlefield.location)}")
                appendLine("   ${GameI18n.biome(language, offer.battlefield.biome)} · ${GameI18n.difficulty(language, offer.difficulty)} · ${GameI18n.t(language, "reward")} $rewardPercent%")
                appendLine("   ${GameI18n.t(language, "intel")} (${GameI18n.intelLevel(language, offer.difficulty)}): ${localizedIntel(language, offer)}")
            }
            appendLine()
            append(GameI18n.t(language, "operation_choose_unlimited"))
        }
        val keyboard = InlineKeyboardMarkup(
            offers.mapIndexed { index, offer ->
                listOf(InlineKeyboardButton("${index + 1}. ${offer.difficulty.icon} ${GameI18n.battlefield(language, offer.battlefield.location)}", "op:$offerVersion:${offer.slot}"))
            } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"))),
        )
        telegram.sendMessage(chatId, text, keyboard)
    }

    private fun showDeployment(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parsed = parseSelection(callbackData, "op") ?: return staleSelection(chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != parsed.expectedOfferVersion) return staleSelection(chatId)
        val operation = offersFor(telegramId, parsed.expectedOfferVersion).getOrNull(parsed.slot) ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(chatId, army, language)
        val group = inventory.battleSnapshot(army)
        if (group.units.isEmpty() || group.usedCp < forceTiers.minimumBattleCp || group.usedCp > group.cpLimit) return armyMenu(telegramId, chatId)
        val map = battleEngine.mapFor(operation)

        val text = buildString {
            appendLine("🗺️ ${GameI18n.t(language, map.nameKey)}")
            appendLine("${GameI18n.battlefield(language, operation.battlefield.location)} · ${GameI18n.biome(language, operation.battlefield.biome)}")
            appendLine()
            map.objectives.forEachIndexed { index, objective ->
                appendLine("${index + 1} — ${GameI18n.t(language, objective.nameKey)} · ${GameI18n.t(language, "capture_steps", objective.captureSteps)}")
            }
            appendLine()
            appendLine("${GameI18n.t(language, "active_group")}: ${army.activeGroup.name} · ${group.usedCp}/${group.cpLimit} CP")
            append(GameI18n.t(language, "choose_entry"))
        }
        val buttons = map.playerEntries.mapIndexed { index, entry ->
            listOf(
                InlineKeyboardButton(
                    "${('A'.code + index).toChar()} · ${GameI18n.t(language, entry.nameKey)}",
                    "deploy:${parsed.expectedOfferVersion}:${parsed.slot}:${entry.id}:${army.activeGroup.presetNo}.${group.version}",
                ),
            )
        } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "other_operations"), "nav:battle")))
        telegram.sendPhoto(
            chatId,
            properties.publicBaseUrl.trimEnd('/') + "/assets/maps/personal/${map.id}.png?v=${map.version}",
            text,
            InlineKeyboardMarkup(buttons),
        )
    }

    private fun showObjectives(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 5 || parts[0] != "deploy") return staleSelection(chatId)
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return staleSelection(chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(chatId)
        val entryId = parts[3]
        val expectedGroup = parseGroupBinding(parts[4]) ?: return staleSelection(chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return staleSelection(chatId)
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(chatId, army, language)
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return staleSelection(chatId)
        val map = battleEngine.mapFor(operation)
        val entry = map.playerEntries.firstOrNull { it.id == entryId } ?: return staleSelection(chatId)

        val text = buildString {
            appendLine("🧭 ${GameI18n.t(language, "route")}")
            appendLine("${army.activeGroup.name} → ${GameI18n.t(language, entry.nameKey)}")
            appendLine()
            append(GameI18n.t(language, "choose_objective"))
        }
        val buttons = map.objectives.mapIndexed { index, objective ->
            listOf(
                InlineKeyboardButton(
                    "${index + 1} · ${GameI18n.t(language, objective.nameKey)}",
                    "objective:$expectedOfferVersion:$slot:$entryId:${objective.id}:${expectedGroup.presetNo}.${expectedGroup.version}",
                ),
            )
        } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "other_operations"), "nav:battle")))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(buttons))
    }

    private fun showTactics(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 6 || parts[0] != "objective") return staleSelection(chatId)
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return staleSelection(chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(chatId)
        val entryId = parts[3]
        val objectiveId = parts[4]
        val expectedGroup = parseGroupBinding(parts[5]) ?: return staleSelection(chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return staleSelection(chatId)
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(chatId, army, language)
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return staleSelection(chatId)
        val map = battleEngine.mapFor(operation)
        val entry = map.playerEntries.firstOrNull { it.id == entryId } ?: return staleSelection(chatId)
        val objective = map.objectives.firstOrNull { it.id == objectiveId } ?: return staleSelection(chatId)

        val text = buildString {
            appendLine("🎯 ${GameI18n.battlefield(language, operation.battlefield.location)}")
            appendLine("${GameI18n.t(language, "route")}: ${army.activeGroup.name} → ${GameI18n.t(language, entry.nameKey)} → ${GameI18n.t(language, objective.nameKey)}")
            appendLine("${GameI18n.t(language, "intel")} (${GameI18n.intelLevel(language, operation.difficulty)}): ${localizedIntel(language, operation)}")
            appendLine()
            appendLine(GameI18n.t(language, "choose_tactic_spatial"))
            Tactic.entries.forEach {
                appendLine("${it.icon} ${GameI18n.tactic(language, it)} — ${GameI18n.tacticHint(language, it)}")
            }
        }
        val buttons = Tactic.entries.map { tactic ->
            InlineKeyboardButton(
                "${tactic.icon} ${GameI18n.tactic(language, tactic)}",
                "fight:$expectedOfferVersion:$slot:$entryId:$objectiveId:${tactic.code}:${expectedGroup.presetNo}.${expectedGroup.version}",
            )
        }.chunked(2) + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "other_operations"), "nav:battle")))
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun resolveBattle(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 7 || parts[0] != "fight") return staleSelection(chatId)
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return staleSelection(chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(chatId)
        val entryId = parts[3]
        val objectiveId = parts[4]
        val tactic = Tactic.fromCode(parts[5]) ?: return staleSelection(chatId)
        val expectedGroup = parseGroupBinding(parts[6]) ?: return staleSelection(chatId)
        val player = player(telegramId, lock = true)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return staleSelection(chatId)
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(chatId, army, language)
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return staleSelection(chatId)
        val group = inventory.battleSnapshot(army)
        if (group.usedCp < forceTiers.minimumBattleCp || group.usedCp > group.cpLimit) return armyMenu(telegramId, chatId)
        val map = battleEngine.mapFor(operation)
        if (map.playerEntries.none { it.id == entryId } || map.objectives.none { it.id == objectiveId }) return staleSelection(chatId)
        val plan = DeploymentPlan(entryId, objectiveId)
        val battleId = UUID.randomUUID()
        val baseBattle = battleEngine.resolve(
            properties.battleServerSalt,
            "$telegramId:${todayKey()}:$expectedOfferVersion:$slot",
            player.commanderLevel,
            operation,
            tactic,
            group,
            plan,
        )
        val economyBonus = campaigns.activeEconomyBonus(player.allianceCode)
        val outcomeCredits = EconomyPolicy.applyPercent(baseBattle.outcomeCredits, economyBonus?.creditsPercent ?: 100)
        val destructionCredits = EconomyPolicy.applyPercent(baseBattle.destructionCredits, economyBonus?.creditsPercent ?: 100)
        val outcomeXp = EconomyPolicy.applyPercent(baseBattle.outcomeXp, economyBonus?.xpPercent ?: 100)
        val destructionXp = EconomyPolicy.applyPercent(baseBattle.destructionXp, economyBonus?.xpPercent ?: 100)
        val battle = baseBattle.copy(
            outcomeCredits = outcomeCredits,
            destructionCredits = destructionCredits,
            outcomeXp = outcomeXp,
            destructionXp = destructionXp,
            credits = outcomeCredits + destructionCredits,
            xp = outcomeXp + destructionXp,
        )
        val spatial = requireNotNull(battle.spatial)
        val xpAfter = player.xp + battle.xp
        val levelAfter = CommanderProgression.levelForXp(xpAfter)

        val updated = jdbc.sql(
            """
            UPDATE players
               SET battle_offer_version = battle_offer_version + 1,
                   xp = xp + :xp,
                   credits = credits + :credits,
                   materials = materials + :materials,
                   commander_level = :commanderLevel,
                   command_capacity = :commandCapacity,
                   victories = victories + CASE WHEN :victory THEN 1 ELSE 0 END,
                   defeats = defeats + CASE WHEN :victory THEN 0 ELSE 1 END,
                   current_streak = CASE WHEN :victory THEN current_streak + 1 ELSE 0 END,
                   best_streak = CASE WHEN :victory THEN GREATEST(best_streak, current_streak + 1) ELSE best_streak END,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id AND battle_offer_version = :expectedOfferVersion
            """.trimIndent(),
        ).param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("materials", battle.materials)
            .param("commanderLevel", levelAfter)
            .param("commandCapacity", ForceTierCatalog.capacityForLevel(levelAfter))
            .param("victory", battle.victory)
            .param("id", telegramId)
            .param("expectedOfferVersion", expectedOfferVersion)
            .update()

        if (updated == 0) return staleSelection(chatId)
        val casualties = inventory.destroyPersonalCasualties(telegramId, army, group, spatial.playerUnits, battleId)

        jdbc.sql(
            """
            INSERT INTO battles(
                id, player_telegram_id, victory, player_power, enemy_power,
                xp_reward, credits_reward, research_points_reward, materials_reward,
                battle_seed, commander_level_snapshot, seed_hash, engine_version, location, biome, difficulty,
                enemy_archetype, tactic, rounds, events_json,
                battle_group_id, group_snapshot_json, battle_group_version, composition_power, tactic_fit, counter_bonus,
                map_id, map_version, deployment_entry, primary_objective,
                enemy_entry, enemy_objective, enemy_tactic, end_reason,
                map_snapshot_json, enemy_group_snapshot_json, spatial_events_json, objective_state_json,
                force_tier_id, player_deployed_cp, enemy_deployed_cp,
                player_units_survived, player_units_lost,
                outcome_credits, destruction_credits, outcome_xp, destruction_xp, enemy_units_destroyed
            )
            VALUES (
                :id, :playerId, :victory, :playerPower, :enemyPower,
                :xp, :credits, 0, :materials,
                :battleSeed, :commanderLevel, :seedHash, 7, :location, :biome, :difficulty,
                :enemy, :tactic, :rounds, CAST(:events AS jsonb),
                :groupId, CAST(:groupSnapshot AS jsonb), :groupVersion, :compositionPower, 0, 0,
                :mapId, :mapVersion, :deploymentEntry, :primaryObjective,
                :enemyEntry, :enemyObjective, :enemyTactic, :endReason,
                CAST(:mapSnapshot AS jsonb), CAST(:enemyGroupSnapshot AS jsonb),
                CAST(:spatialEvents AS jsonb), CAST(:objectiveState AS jsonb),
                :forceTier, :playerDeployedCp, :enemyDeployedCp,
                :playerUnitsSurvived, :playerUnitsLost,
                :outcomeCredits, :destructionCredits, :outcomeXp, :destructionXp, :enemyUnitsDestroyed
            )
            """.trimIndent(),
        ).param("id", battleId)
            .param("playerId", telegramId)
            .param("victory", battle.victory)
            .param("playerPower", battle.playerPower)
            .param("enemyPower", battle.enemyPower)
            .param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("materials", battle.materials)
            .param("battleSeed", battle.seed)
            .param("commanderLevel", player.commanderLevel)
            .param("seedHash", battle.seedHash)
            .param("location", operation.battlefield.location)
            .param("biome", operation.battlefield.biome)
            .param("difficulty", operation.difficulty.name)
            .param("enemy", operation.enemy.name)
            .param("tactic", tactic.name)
            .param("rounds", battle.events.size)
            .param("events", objectMapper.writeValueAsString(battle.events))
            .param("groupSnapshot", objectMapper.writeValueAsString(group))
            .param("groupId", group.id)
            .param("groupVersion", group.version)
            .param("compositionPower", battle.compositionPower)
            .param("mapId", spatial.map.id)
            .param("mapVersion", spatial.map.version)
            .param("deploymentEntry", spatial.playerPlan.entryId)
            .param("primaryObjective", spatial.playerPlan.objectiveId)
            .param("enemyEntry", spatial.enemyEntryId)
            .param("enemyObjective", spatial.enemyObjectiveId)
            .param("enemyTactic", spatial.enemyTactic.name)
            .param("endReason", spatial.endReason.name)
            .param("mapSnapshot", objectMapper.writeValueAsString(spatial.map))
            .param("enemyGroupSnapshot", objectMapper.writeValueAsString(spatial.enemyGroup))
            .param("spatialEvents", objectMapper.writeValueAsString(spatial.events))
            .param("objectiveState", objectMapper.writeValueAsString(spatial.objectives))
            .param("forceTier", battle.forceTierId)
            .param("playerDeployedCp", battle.playerDeployedCp)
            .param("enemyDeployedCp", battle.enemyDeployedCp)
            .param("playerUnitsSurvived", casualties.survived)
            .param("playerUnitsLost", casualties.lost)
            .param("outcomeCredits", battle.outcomeCredits)
            .param("destructionCredits", battle.destructionCredits)
            .param("outcomeXp", battle.outcomeXp)
            .param("destructionXp", battle.destructionXp)
            .param("enemyUnitsDestroyed", battle.destroyedEnemyUnits)
            .update()

        recordWalletChange(telegramId, "XP", battle.xp.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "CREDITS", battle.credits.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "MATERIALS", battle.materials.toLong(), "BATTLE_REWARD", battleId)

        val fresh = player(telegramId)
        val entry = spatial.map.playerEntries.first { it.id == spatial.playerPlan.entryId }
        val objective = spatial.map.objectives.first { it.id == spatial.playerPlan.objectiveId }
        val highlights = spatialHighlights(language, spatial.events, spatial.map)
        val objectives = spatial.objectives.joinToString("\n") { state ->
            val definition = spatial.map.objectives.first { it.id == state.id }
            val marker = when (state.owner) {
                BattleSide.PLAYER -> "🟦"
                BattleSide.ENEMY -> "🟥"
                null -> "⬜"
            }
            "$marker ${GameI18n.t(language, definition.nameKey)}"
        }
        val playerRemaining = spatial.playerUnits.sumOf { it.remainingQuantity }
        val enemyRemaining = spatial.enemyUnits.sumOf { it.remainingQuantity }
        val outcome = GameI18n.t(language, if (battle.victory) "victory" else "withdrawal")
        val forceTier = forceTiers.forDeployedCp(requireNotNull(battle.playerDeployedCp))
        val levelUp = if (fresh.commanderLevel > player.commanderLevel) {
            "\n⭐ ${GameI18n.t(language, "new_level")}: ${fresh.commanderLevel} · ${fresh.commandCapacity} CP"
        } else ""
        val report = buildString {
            appendLine(GameI18n.t(language, "battle_complete"))
            appendLine("${GameI18n.battlefield(language, operation.battlefield.location)} · ${GameI18n.biome(language, operation.battlefield.biome)}")
            appendLine("${tactic.icon} ${GameI18n.tactic(language, tactic)}")
            appendLine("${GameI18n.t(language, "route")}: ${army.activeGroup.name} → ${GameI18n.t(language, entry.nameKey)} → ${GameI18n.t(language, objective.nameKey)}")
            appendLine("${GameI18n.t(language, "enemy_label")}: ${GameI18n.enemy(language, operation.enemy)}")
            appendLine("${GameI18n.t(language, "battle_category")}: ${forceTierLabel(language, forceTier)} · ${battle.playerDeployedCp}:${battle.enemyDeployedCp} CP")
            appendLine()
            appendLine(outcome)
            appendLine("${GameI18n.t(language, "battle_steps")}: ${spatial.steps}")
            appendLine("${GameI18n.t(language, "end_reason")}: ${GameI18n.t(language, endReasonKey(spatial.endReason))}")
            appendLine("${GameI18n.t(language, "units_remaining")}: $playerRemaining : $enemyRemaining")
            appendLine()
            appendLine(GameI18n.t(language, "objective_control"))
            appendLine(objectives)
            appendLine()
            if (highlights.isNotBlank()) {
                appendLine(highlights)
                appendLine()
            }
            appendLine("+${battle.xp} XP")
            appendLine("+${battle.credits} Credits")
            appendLine(GameI18n.t(language, "destruction_reward", battle.destroyedEnemyUnits, battle.destructionCredits, battle.destructionXp))
            economyBonus?.let {
                appendLine(GameI18n.t(language, "weekly_victory_bonus_applied"))
            }
            appendLine("+${battle.materials} Materials")
            appendLine(GameI18n.t(language, "equipment_returned_lost", casualties.survived, casualties.lost))
            appendLine("${GameI18n.t(language, "streak")}: ${fresh.currentStreak}$levelUp")
        }
        telegram.sendMessage(chatId, report.trim(), replayKeyboard(telegramId, language, "replay:b:$battleId"))
    }

    private fun sendPersonalReplay(telegramId: Long, chatId: Long, rawBattleId: String) {
        val language = language(telegramId)
        val battleId = runCatching { UUID.fromString(rawBattleId) }.getOrNull()
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "replay_unavailable"), actionKeyboard(telegramId, language))
        telegram.sendMessage(chatId, GameI18n.t(language, "replay_rendering"))
        runCatching { replays.preparePersonal(telegramId, battleId) }
            .onSuccess { replay ->
                telegram.sendAnimation(
                    chatId,
                    replay.url,
                    GameI18n.t(language, "replay_caption"),
                    replay.width,
                    replay.height,
                    replay.durationSeconds,
                    actionKeyboard(telegramId, language),
                )
            }
            .onFailure { error ->
                logger.warn("Could not render personal replay {} for player {}", battleId, telegramId, error)
                telegram.sendMessage(chatId, GameI18n.t(language, "replay_unavailable"), actionKeyboard(telegramId, language))
            }
    }

    private fun sendWeeklyReplay(telegramId: Long, chatId: Long, rawMatchupId: String) {
        val language = language(telegramId)
        val matchupId = runCatching { UUID.fromString(rawMatchupId) }.getOrNull()
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "replay_unavailable"), actionKeyboard(telegramId, language))
        telegram.sendMessage(chatId, GameI18n.t(language, "replay_rendering"))
        runCatching { replays.prepareWeekly(player(telegramId).allianceCode, matchupId) }
            .onSuccess { replay ->
                telegram.sendAnimation(
                    chatId,
                    replay.url,
                    GameI18n.t(language, "weekly_replay_caption"),
                    replay.width,
                    replay.height,
                    replay.durationSeconds,
                    actionKeyboard(telegramId, language),
                )
            }
            .onFailure { error ->
                logger.warn("Could not render weekly replay {} for player {}", matchupId, telegramId, error)
                telegram.sendMessage(chatId, GameI18n.t(language, "replay_unavailable"), actionKeyboard(telegramId, language))
            }
    }

    private fun staleSelection(chatId: Long) {
        val language = languageByChat(chatId)
        telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(chatId, language))
    }

    private fun weeklyUnitsReserved(chatId: Long, army: Army, language: GameLanguage) {
        telegram.sendMessage(chatId, GameI18n.t(language, "weekly_units_reserved"), armyKeyboard(army, language))
    }

    private fun offersFor(telegramId: Long, offerVersion: Long): List<OperationOffer> =
        battleEngine.offers(properties.battleServerSalt, "$telegramId:${todayKey()}:$offerVersion")

    private fun parseSelection(data: String, prefix: String): Selection? {
        val parts = data.split(':')
        if (parts.size != 3 || parts[0] != prefix) return null
        return Selection(parts[1].toLongOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
    }

    private fun parseGroupBinding(value: String): GroupBinding? {
        val parts = value.split('.')
        if (parts.size != 2) return null
        return GroupBinding(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
    }

    private fun armyMenu(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val army = inventory.army(telegramId)
        val active = army.activeGroup
        val snapshot = active.units.takeIf { it.isNotEmpty() }?.let { inventory.battleSnapshot(army) }
        val text = buildString {
            appendLine(GameI18n.t(language, "army_title"))
            appendLine("${GameI18n.t(language, "cp_limit")}: ${groupCp(army)}/${army.cpLimit} CP")
            val deployedCp = groupCp(army)
            if (deployedCp >= forceTiers.minimumBattleCp) {
                appendLine("${GameI18n.t(language, "battle_category")}: ${forceTierLabel(language, forceTiers.forDeployedCp(deployedCp))}")
            } else {
                appendLine(GameI18n.t(language, "group_below_minimum", deployedCp, forceTiers.minimumBattleCp))
            }
            appendLine()
            army.groups.forEach { group ->
                val mark = if (group.active) "✅" else "▫️"
                val cp = group.units.sumOf { equipment.require(it.code).cpCost }
                appendLine("$mark ${group.presetNo}. ${group.name} · $cp/${army.cpLimit} CP · ${group.units.size}")
            }
            appendLine()
            appendLine(GameI18n.t(language, "active_group") + ": " + active.name)
            if (active.units.isEmpty()) appendLine(GameI18n.t(language, "army_empty"))
            else active.units.groupBy { it.code to it.level }
                .toSortedMap(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
                .forEach { (key, units) ->
                    val reserved = units.count { it.reservedWeekKey != null }.takeIf { it > 0 }?.let { " · 🔒$it" }.orEmpty()
                    appendLine("• ${units.size}× ${unitLabel(key.first, key.second, language)}$reserved")
                }
            snapshot?.let {
                appendLine()
                appendLine("${GameI18n.t(language, "composition_power")}: ${battleEngine.compositionPower(it)}")
            }
            appendLine()
            append(GameI18n.t(language, "army_hint"))
        }
        telegram.sendMessage(chatId, text, armyKeyboard(army, language))
    }

    private fun armyKeyboard(army: Army, language: GameLanguage): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        rows += army.groups.map { group ->
            val mark = if (group.active) "✅ " else ""
            InlineKeyboardButton("$mark${group.presetNo}. ${group.name}", "army:preset:${group.presetNo}")
        }
        val availability = GameUiPolicy.equipmentSelection(army)
        equipment.units.forEach { definition ->
            val state = availability[definition.code] ?: return@forEach
            val actions = mutableListOf<InlineKeyboardButton>()
            if (state.selected > 0 && army.activeGroup.units.size > 1) {
                actions += InlineKeyboardButton("➖ ${definition.emoji} ×${state.selected}", "army:remove:${definition.code}")
            }
            if (state.selected < state.available) {
                actions += InlineKeyboardButton(
                    "➕ ${definition.name(language)} ${state.selected}/${state.available}",
                    "army:add:${definition.code}",
                )
            }
            if (actions.isNotEmpty()) rows += actions
        }
        rows += listOf(
            InlineKeyboardButton(GameI18n.t(language, "shop"), "nav:shop"),
            InlineKeyboardButton(GameI18n.t(language, "upgrade"), "nav:upgrade"),
        )
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "battle"), "nav:battle"))
        return InlineKeyboardMarkup(rows)
    }

    private fun shopMenu(telegramId: Long, chatId: Long) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val text = buildString {
            appendLine(GameI18n.t(language, "shop_title"))
            appendLine("Credits: ${player.credits} · Materials: ${player.materials}")
            appendLine()
            append(GameI18n.t(language, "shop_hint"))
        }
        val rows = GameUiPolicy.shopOrder(equipment.units, player.commanderLevel).map { definition ->
            val lock = if (player.commanderLevel < definition.unlockLevel) "🔒 L${definition.unlockLevel}" else "${definition.cpCost} CP"
            listOf(InlineKeyboardButton("${definition.emoji} ${definition.name(language)} · $lock", "shop:view:${definition.code}"))
        } + listOf(listOf(
            InlineKeyboardButton(GameI18n.t(language, "upgrade"), "nav:upgrade"),
            InlineKeyboardButton(GameI18n.t(language, "army"), "nav:army"),
        ))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
    }

    private fun shopDetails(telegramId: Long, chatId: Long, code: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val definition = equipment.get(code) ?: return shopMenu(telegramId, chatId)
        val stats = definition.stats
        val state = if (player.commanderLevel >= definition.unlockLevel) "✅" else "🔒 ${GameI18n.t(language, "level")} ${definition.unlockLevel}"
        val text = """
            ${definition.emoji} ${definition.name(language)} · $state

            ⚔ ${GameI18n.t(language, "stat_attack")}: ${stats.attack}
            🛡 ${GameI18n.t(language, "stat_armor")}: ${stats.armor}
            🏎 ${GameI18n.t(language, "stat_mobility")}: ${stats.mobility}
            🔭 ${GameI18n.t(language, "stat_recon")}: ${stats.recon}
            📡 ${GameI18n.t(language, "stat_support")}: ${stats.support}
            🛞 ${GameI18n.t(language, "stat_map_movement")}: ${definition.spatial.movementPoints}
            🎯 ${GameI18n.t(language, "stat_weapon_range")}: ${definition.spatial.minimumRange}–${definition.spatial.weaponRange}
            👁 ${GameI18n.t(language, "stat_sight")}: ${definition.spatial.sightRange}
            🔥 ${GameI18n.t(language, "stat_fire_mode")}: ${GameI18n.fireMode(language, definition.spatial.fireMode)}
            CP: ${definition.cpCost}

            ${GameI18n.t(language, "buy")}: ${definition.buyCredits} Credits
            ${GameI18n.t(language, "craft")}: ${definition.craftCredits} Credits + ${definition.craftMaterials} Materials
            ${GameI18n.t(language, "upgrade_growth")}
        """.trimIndent()
        val keyboard = InlineKeyboardMarkup(listOf(
            listOf(
                InlineKeyboardButton("💳 ${GameI18n.t(language, "buy")} ×1", "shop:buy:${definition.code}:1"),
                InlineKeyboardButton("💳 ×5", "shop:buy:${definition.code}:5"),
                InlineKeyboardButton("💳 ×25", "shop:buy:${definition.code}:25"),
            ),
            listOf(
                InlineKeyboardButton("🛠 ${GameI18n.t(language, "craft")} ×1", "shop:craft:${definition.code}:1"),
                InlineKeyboardButton("🛠 ×5", "shop:craft:${definition.code}:5"),
                InlineKeyboardButton("🛠 ×25", "shop:craft:${definition.code}:25"),
            ),
            listOf(InlineKeyboardButton("↩️ ${GameI18n.t(language, "shop")}", "nav:shop")),
        ))
        telegram.sendPhoto(chatId, properties.publicBaseUrl.trimEnd('/') + definition.iconPath, text, keyboard)
    }

    private fun acquireUnit(telegramId: Long, chatId: Long, payload: String, craft: Boolean) {
        val language = language(telegramId)
        val parts = payload.split(':')
        val code = parts.firstOrNull().orEmpty()
        val quantity = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val action = inventory.acquire(telegramId, code, craft, quantity)
        telegram.sendMessage(chatId, actionMessage(action, language))
        shopDetails(telegramId, chatId, code)
    }

    private fun upgradeMenu(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val army = inventory.army(telegramId)
        val player = player(telegramId)
        val upgradeGroups = army.inventory.filter { it.level < 5 && it.reservedWeekKey == null }
            .groupBy { it.code to it.level }
            .toSortedMap(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
        val details = upgradeGroups.entries.joinToString("\n") { (key, units) ->
            val definition = equipment.require(key.first)
            "• ${units.size}× ${unitLabel(key.first, key.second, language)} → L${key.second + 1}: ${definition.upgradeCredits(key.second)}C + ${definition.upgradeMaterials(key.second)}M ${GameI18n.t(language, "per_unit")}"
        }.ifBlank { GameI18n.t(language, "all_units_maximum") }
        val rows = upgradeGroups.flatMap { (key, units) ->
            val definition = equipment.require(key.first)
            val amounts = listOf(1, 5, 25).filter { it == 1 || units.size >= it }
            listOf(amounts.map { quantity ->
                InlineKeyboardButton(
                    "⬆️ ${definition.emoji} L${key.second} ×$quantity",
                    "unit:upgrade-batch:${definition.code}:${key.second}:$quantity",
                )
            })
        } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "army"), "nav:army")))
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "upgrade_title")}\nCredits: ${player.credits} · Materials: ${player.materials}\n\n${GameI18n.t(language, "upgrade_growth")}\n\n$details",
            InlineKeyboardMarkup(rows),
        )
    }

    private fun upgradeBatch(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 3) return upgradeMenu(telegramId, chatId)
        val level = parts[1].toIntOrNull() ?: return upgradeMenu(telegramId, chatId)
        val quantity = parts[2].toIntOrNull() ?: return upgradeMenu(telegramId, chatId)
        val language = language(telegramId)
        val action = inventory.upgradeBatch(telegramId, parts[0], level, quantity)
        telegram.sendMessage(chatId, actionMessage(action, language))
        upgradeMenu(telegramId, chatId)
    }

    private fun upgradeUnit(telegramId: Long, chatId: Long, rawId: String) {
        val language = language(telegramId)
        val unitId = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return upgradeMenu(telegramId, chatId)
        val action = inventory.upgrade(telegramId, unitId)
        telegram.sendMessage(chatId, actionMessage(action, language))
        upgradeMenu(telegramId, chatId)
    }

    private fun toggleUnit(telegramId: Long, chatId: Long, rawId: String) {
        val language = language(telegramId)
        val unitId = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return armyMenu(telegramId, chatId)
        val action = inventory.toggleInActiveGroup(telegramId, unitId)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun addUnitType(telegramId: Long, chatId: Long, code: String) {
        val language = language(telegramId)
        val action = inventory.addUnitTypeToActiveGroup(telegramId, code)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun removeUnitType(telegramId: Long, chatId: Long, code: String) {
        val language = language(telegramId)
        val action = inventory.removeUnitTypeFromActiveGroup(telegramId, code)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun activatePreset(telegramId: Long, chatId: Long, rawPreset: String) {
        inventory.activatePreset(telegramId, rawPreset.toIntOrNull() ?: 0)
        armyMenu(telegramId, chatId)
    }

    private fun actionMessage(action: EquipmentAction, language: GameLanguage): String {
        val name = action.definition?.name(language).orEmpty()
        return when (action.status) {
            EquipmentActionStatus.SUCCESS -> if (action.upgraded && action.quantity > 1) {
                GameI18n.t(language, "equipment_upgrade_batch_success", action.quantity, name, action.unit?.level ?: 1)
            } else if (action.quantity > 1) {
                GameI18n.t(language, "equipment_batch_success", action.quantity, name)
            } else {
                GameI18n.t(language, "equipment_success", name, action.unit?.level ?: 1)
            }
            EquipmentActionStatus.LOCKED -> GameI18n.t(language, "equipment_locked", action.definition?.unlockLevel ?: 1)
            EquipmentActionStatus.RESERVED -> GameI18n.t(language, "equipment_reserved")
            EquipmentActionStatus.INSUFFICIENT_RESOURCES -> GameI18n.t(language, "insufficient_resources")
            EquipmentActionStatus.MAX_LEVEL -> GameI18n.t(language, "max_level")
            EquipmentActionStatus.GROUP_FULL -> GameI18n.t(language, "group_full")
            EquipmentActionStatus.NO_AVAILABLE_UNIT -> GameI18n.t(language, "no_available_unit", name)
            EquipmentActionStatus.LAST_UNIT -> GameI18n.t(language, "last_unit")
            EquipmentActionStatus.NOT_FOUND -> GameI18n.t(language, "stale")
        }
    }

    private fun unitLabel(unit: OwnedUnit, language: GameLanguage): String = unitLabel(unit.code, unit.level, language)

    private fun unitLabel(code: String, level: Int, language: GameLanguage): String {
        val definition = equipment.require(code)
        return "${definition.emoji} ${definition.name(language)} L$level · ${definition.cpCost}CP"
    }

    private fun groupCp(army: Army): Int = army.activeGroup.units.sumOf { equipment.require(it.code).cpCost }

    private fun forceTierLabel(language: GameLanguage, tier: ForceTier): String =
        "${GameI18n.t(language, tier.nameKey)} · ${tier.minCp}–${tier.maxCp} CP"

    private fun claimDailyReward(telegramId: Long, chatId: Long) {
        val before = player(telegramId, lock = true)
        val language = GameLanguage.fromStored(before.language)
        val today = LocalDate.now(ZoneId.of(properties.gameTimezone))
        if (before.dailyRewardLastClaim == today) {
            telegram.sendMessage(chatId, GameI18n.t(language, "daily_already_claimed", before.dailyRewardStreak), actionKeyboard(telegramId, language))
            return
        }
        val baseReward = DailyRewardPolicy.reward(before.dailyRewardStreak, before.dailyRewardLastClaim, today)
        val economyBonus = campaigns.activeEconomyBonus(before.allianceCode)
        val reward = baseReward.copy(credits = EconomyPolicy.applyPercent(baseReward.credits, economyBonus?.creditsPercent ?: 100))
        val referenceId = UUID.nameUUIDFromBytes("daily:$telegramId:$today".toByteArray(StandardCharsets.UTF_8))
        jdbc.sql(
            """
            UPDATE players
               SET credits = credits + :credits,
                   daily_reward_streak = :streak,
                   daily_reward_last_claim = :today,
                   daily_reward_claims = daily_reward_claims + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id AND (daily_reward_last_claim IS NULL OR daily_reward_last_claim < :today)
            """.trimIndent(),
        ).param("credits", reward.credits).param("streak", reward.streak).param("today", today).param("id", telegramId).update()
        recordWalletChange(telegramId, "CREDITS", reward.credits, "DAILY_REWARD", referenceId)
        val bonusUnit = if (reward.grantsUnit) {
            val unlocked = equipment.units.filter { it.unlockLevel <= before.commanderLevel }
            val digest = MessageDigest.getInstance("SHA-256").digest("${properties.battleServerSalt}:$telegramId:$today".toByteArray(StandardCharsets.UTF_8))
            inventory.grantDailyUnit(telegramId, unlocked[Math.floorMod(digest.take(4).fold(0) { acc, byte -> acc * 31 + byte }, unlocked.size)])
        } else null
        val bonusUnitText = bonusUnit?.let { "\n${GameI18n.t(language, "daily_bonus_unit", unitLabel(it, language))}" }.orEmpty()
        val economyBonusText = economyBonus?.let { "\n${GameI18n.t(language, "weekly_victory_bonus_applied")}" }.orEmpty()
        telegram.sendMessage(chatId, GameI18n.t(language, "daily_claimed", reward.credits, reward.streak) + bonusUnitText + economyBonusText, actionKeyboard(telegramId, language))
    }

    private fun contribute(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, GameI18n.t(language, "choose_country"), recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            return
        }
        val army = inventory.army(telegramId)
        val sent = campaigns.contributions(telegramId).associateBy { it.presetNo }
        val text = buildString {
            appendLine(frontLocalized(language, "🚩 GROUPS ON THE FRONT", "🚩 ОТРЯДЫ НА ФРОНТЕ"))
            appendLine(frontLocalized(language, "Choose any of your three groups. Each sent group receives its own entry and tactic.", "Можно отправить любой из трёх отрядов. Для каждого отдельно выбираются вход и тактика."))
            appendLine()
            army.groups.forEach { group ->
                val cp = group.units.sumOf { equipment.require(it.code).cpCost }
                val composition = if (group.units.isEmpty()) {
                    frontLocalized(language, "empty", "пусто")
                } else {
                    group.units.groupBy { it.code to it.level }.entries.joinToString(", ") { (key, units) ->
                        "${units.size}× ${unitLabel(key.first, key.second, language)}"
                    }
                }
                appendLine("${group.presetNo}. ${group.name} · $cp/${army.cpLimit} CP")
                appendLine("   $composition")
                sent[group.presetNo]?.let {
                    appendLine("   ✅ ${it.entryId ?: "—"} · ${GameI18n.tactic(language, it.tactic)}")
                }
            }
            appendLine()
            append(frontLocalized(language, "A sent unit cannot be used in personal battles or another front group until it is withdrawn or survives the weekly battle.", "Отправленная машина недоступна для обычных боёв и другого фронтового отряда, пока её не отозвали или она не вернулась после недельной битвы."))
        }
        val buttons = army.groups.map { group ->
            val contribution = sent[group.presetNo]
            if (contribution == null) {
                listOf(InlineKeyboardButton("➕ ${group.name}", "front:send:${group.presetNo}.${group.version}"))
            } else {
                listOf(
                    InlineKeyboardButton("🔄 ${group.name}", "front:send:${group.presetNo}.${group.version}"),
                    InlineKeyboardButton("↩️", "front:withdraw:${contribution.id}"),
                )
            }
        } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "front"), "nav:front")))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(buttons))
    }

    private fun frontEntries(telegramId: Long, chatId: Long, binding: String) {
        val expected = parseGroupBinding(binding) ?: return staleSelection(chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(chatId)
        if (group.units.isEmpty()) return telegram.sendMessage(chatId, GameI18n.t(language, "army_empty"), actionKeyboard(telegramId, language))
        val deployment = campaigns.frontDeployment(alliance)
            ?: return telegram.sendMessage(chatId, frontLocalized(language, "No open weekly battle was found.", "Открытый недельный бой не найден."), actionKeyboard(telegramId, language))
        val text = buildString {
            appendLine("🚩 ${group.name}")
            appendLine(frontLocalized(language, "Choose an edge entry for this group:", "Выберите край карты для входа этого отряда:"))
        }
        val buttons = deployment.entries.map { entry ->
            listOf(InlineKeyboardButton("${entry.id} · ${GameI18n.t(language, entry.nameKey)}", "front:entry:$binding:${entry.id}"))
        }
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun frontTactics(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 2) return staleSelection(chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(chatId)
        val entryId = parts[1]
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(chatId)
        val entries = p.allianceCode?.let { campaigns.frontDeployment(it)?.entries }.orEmpty()
        val entry = entries.firstOrNull { it.id == entryId } ?: return staleSelection(chatId)
        val text = buildString {
            appendLine("🚩 ${group.name} → ${entry.id} · ${GameI18n.t(language, entry.nameKey)}")
            appendLine(frontLocalized(language, "Choose movement and target priority:", "Выберите порядок движения и приоритет целей:"))
            Tactic.entries.forEach { appendLine("${it.icon} ${GameI18n.tactic(language, it)} — ${GameI18n.tacticHint(language, it)}") }
        }
        val buttons = Tactic.entries.map { tactic ->
            InlineKeyboardButton("${tactic.icon} ${GameI18n.tactic(language, tactic)}", "front:commit:${parts[0]}:$entryId:${tactic.code}")
        }.chunked(2)
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun commitFrontGroup(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 3) return staleSelection(chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(chatId)
        val tactic = Tactic.fromCode(parts[2]) ?: return staleSelection(chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode ?: return staleSelection(chatId)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(chatId)
        val snapshot = inventory.battleSnapshot(army, group)
        val outcome = campaigns.contribute(telegramId, alliance, group.presetNo, snapshot, group.units.map { it.id }, parts[1], tactic, language)
        telegram.sendMessage(chatId, outcome.message)
        contribute(telegramId, p.firstName, chatId)
    }

    private fun withdrawFrontGroup(telegramId: Long, chatId: Long, contributionId: Long?) {
        if (contributionId == null) return staleSelection(chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val outcome = campaigns.withdraw(telegramId, contributionId, language)
        telegram.sendMessage(chatId, outcome.message)
        contribute(telegramId, p.firstName, chatId)
    }

    private fun profileText(telegramId: Long, firstName: String? = null): String {
        if (firstName != null) ensurePlayer(telegramId, firstName)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode?.let { AllianceCatalog.option(it, language).label } ?: GameI18n.t(language, "not_selected")
        val battles = p.victories + p.defeats
        val winRate = if (battles == 0) 0 else p.victories * 100 / battles
        val progress = CommanderProgression.progress(p.xp)
        val levelProgress = "${progress.earnedInLevel}/${progress.requiredForNextLevel} XP"
        val economyBonus = campaigns.activeEconomyBonus(p.allianceCode)
        val economyBonusText = economyBonus?.let {
            val endDate = it.endsAt.atZone(ZoneId.of(properties.gameTimezone)).toLocalDate()
            "\n${GameI18n.t(language, "weekly_victory_bonus_profile", endDate)}"
        }.orEmpty()
        return """
            🪖 ${GameI18n.t(language, "commander")} ${p.displayName}

            ${GameI18n.t(language, "alliance")}: $alliance
            ${GameI18n.t(language, "level")}: ${p.commanderLevel} · $levelProgress
            ${GameI18n.t(language, "victories")}: ${p.victories}/$battles · $winRate%
            ${GameI18n.t(language, "streak")}: ${p.currentStreak} · ${GameI18n.t(language, "record")} ${p.bestStreak}

            Credits: ${p.credits}
            Materials: ${p.materials}
            ${GameI18n.t(language, "command_capacity")}: ${p.commandCapacity} CP
            ${GameI18n.t(language, "daily_reward_streak")}: ${p.dailyRewardStreak}/100$economyBonusText
        """.trimIndent()
    }

    private fun rankingMenu(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val text = frontLocalized(language, "🏆 RATINGS\n\nChoose alliance standings or the commander XP table.", "🏆 РЕЙТИНГИ\n\nВыберите таблицу стран или рейтинг командиров по XP.")
        val keyboard = InlineKeyboardMarkup(
            listOf(
                listOf(InlineKeyboardButton(frontLocalized(language, "🌍 Alliances", "🌍 Страны"), "rank:alliances:0")),
                listOf(InlineKeyboardButton(frontLocalized(language, "🪖 Players", "🪖 Игроки"), "rank:players")),
            ),
        )
        telegram.sendMessage(chatId, text, keyboard)
    }

    private fun allianceRanking(telegramId: Long, chatId: Long, page: Int) {
        campaigns.ensureCurrentWeek()
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val ranking = rankings.alliances(page, ALLIANCE_RATING_PAGE_SIZE, p.allianceCode)
        val text = buildString {
            appendLine(frontLocalized(language, "🌍 ALLIANCE RATING", "🌍 РЕЙТИНГ СТРАН"))
            appendLine()
            ranking.entries.forEach { row ->
                val marker = if (row.code == p.allianceCode) " ←" else ""
                appendLine("${row.place}. ${AllianceCatalog.option(row.code, language).label} — ${row.rating} · ${row.wins}/${row.games}$marker")
            }
            ranking.ownPlace?.let { appendLine("\n${frontLocalized(language, "Your alliance", "Ваша страна")}: #$it") }
            append("${ranking.page + 1}/${ranking.pages}")
        }
        val nav = mutableListOf<InlineKeyboardButton>()
        if (ranking.page > 0) nav += InlineKeyboardButton("⬅️", "rank:alliances:${ranking.page - 1}")
        if (ranking.page + 1 < ranking.pages) nav += InlineKeyboardButton("➡️", "rank:alliances:${ranking.page + 1}")
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (nav.isNotEmpty()) rows += nav
        rows += listOf(InlineKeyboardButton(frontLocalized(language, "🪖 Players", "🪖 Игроки"), "rank:players"))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
    }

    private fun playerRanking(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val ranking = rankings.players(telegramId)
        val text = buildString {
            appendLine(frontLocalized(language, "🪖 TOP 10 COMMANDERS BY XP", "🪖 ТОП-10 КОМАНДИРОВ ПО XP"))
            appendLine()
            ranking.top.forEach { row ->
                val marker = if (row.telegramId == telegramId) " ←" else ""
                appendLine("${row.place}. ${row.name.take(32)} — ${row.xp} XP · L${row.level}$marker")
            }
            if (ranking.top.none { it.telegramId == telegramId }) {
                appendLine("\n…\n${ranking.own.place}. ${ranking.own.name.take(32)} — ${ranking.own.xp} XP · L${ranking.own.level} ←")
            }
        }
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(frontLocalized(language, "🌍 Alliances", "🌍 Страны"), "rank:alliances:0")))))
    }

    private fun guide(telegramId: Long, chatId: Long, requestedPage: Int) {
        val language = language(telegramId)
        val pageIndex = requestedPage.coerceIn(0, GameGuide.size - 1)
        val page = GameGuide.page(language, pageIndex)
        val text = "📖 ${page.title}\n\n${page.text}\n\n${pageIndex + 1}/${GameGuide.size}"
        val nav = mutableListOf<InlineKeyboardButton>()
        if (pageIndex > 0) nav += InlineKeyboardButton("⬅️", "guide:${pageIndex - 1}")
        if (pageIndex + 1 < GameGuide.size) nav += InlineKeyboardButton("➡️", "guide:${pageIndex + 1}")
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (nav.isNotEmpty()) rows += nav
        rows += listOf(InlineKeyboardButton(frontLocalized(language, "🏠 Menu", "🏠 Меню"), "nav:profile"))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
    }

    private fun front(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        sendFront(telegramId, player.allianceCode, language, chatId)
    }

    private fun sendFront(telegramId: Long, allianceCode: String?, language: GameLanguage, chatId: Long) {
        campaigns.frontMapPath(allianceCode)?.let { path ->
            telegram.sendPhoto(
                chatId,
                properties.publicBaseUrl.trimEnd('/') + path,
                GameI18n.t(language, "weekly_map_image_caption"),
            )
        }
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        campaigns.frontReplayId(allianceCode)?.let { rows += listOf(InlineKeyboardButton(GameI18n.t(language, "replay_button"), "replay:w:$it")) }
        if (allianceCode != null) rows += listOf(InlineKeyboardButton(frontLocalized(language, "🚩 Front groups", "🚩 Отряды на фронте"), "front:manage"))
        rows += actionKeyboard(telegramId, language).inlineKeyboard
        telegram.sendMessage(chatId, campaigns.frontText(telegramId, allianceCode, language), InlineKeyboardMarkup(rows))
    }

    private fun replayKeyboard(telegramId: Long, language: GameLanguage, callbackData: String): InlineKeyboardMarkup = InlineKeyboardMarkup(
        listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "replay_button"), callbackData))) + actionKeyboard(telegramId, language).inlineKeyboard,
    )

    private fun ensurePlayer(telegramId: Long, firstName: String, telegramLanguage: String? = null) {
        val inferred = GameLanguage.fromTelegram(telegramLanguage).code
        jdbc.sql(
            """
            INSERT INTO players(telegram_id, first_name, language, telegram_language)
            VALUES (:id, :firstName, :language, :telegramLanguage)
            ON CONFLICT (telegram_id) DO UPDATE
               SET first_name = EXCLUDED.first_name,
                   telegram_language = COALESCE(EXCLUDED.telegram_language, players.telegram_language),
                   telegram_unavailable_at = NULL,
                   telegram_unavailable_reason = NULL,
                   updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("id", telegramId)
            .param("firstName", firstName.take(128))
            .param("language", inferred)
            .param("telegramLanguage", telegramLanguage?.take(16))
            .update()
        inventory.ensureStarter(telegramId)
    }

    private fun player(telegramId: Long, lock: Boolean = false): Player = jdbc.sql(
        """
        SELECT telegram_id, first_name, nickname, pending_nickname, language, telegram_language,
               alliance_code, commander_level, xp,
               credits, materials, command_capacity, battle_offer_version,
               victories, defeats, current_streak, best_streak,
               daily_reward_streak, daily_reward_last_claim, daily_reward_claims
          FROM players
         WHERE telegram_id = :id${if (lock) " FOR UPDATE" else ""}
        """.trimIndent(),
    ).param("id", telegramId).query(Player::class.java).single()

    private fun recordWalletChange(
        telegramId: Long,
        resource: String,
        delta: Long,
        reason: String,
        referenceId: UUID?,
    ) {
        jdbc.sql(
            """
            INSERT INTO wallet_transactions(player_telegram_id, resource_type, delta, reason, reference_id)
            VALUES (:playerId, :resource, :delta, :reason, :referenceId)
            """.trimIndent(),
        ).param("playerId", telegramId)
            .param("resource", resource)
            .param("delta", delta)
            .param("reason", reason)
            .param("referenceId", referenceId)
            .update()
    }

    private fun recommendedCountryKeyboard(
        language: GameLanguage,
        recommendationLanguage: GameLanguage = language,
    ): InlineKeyboardMarkup = InlineKeyboardMarkup(
        AllianceCatalog.recommended(recommendationLanguage)
            .map { AllianceCatalog.option(it.code, language) }
            .map { listOf(InlineKeyboardButton(it.label, "country:select:${it.code}")) } +
            listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "all_options"), "country:all"))),
    )

    private fun actionKeyboard(telegramId: Long, language: GameLanguage): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        val primary = mutableListOf(InlineKeyboardButton(GameI18n.t(language, "battle"), "nav:battle"))
        val rewardAvailable = GameUiPolicy.dailyRewardAvailable(
            player(telegramId).dailyRewardLastClaim,
            LocalDate.now(ZoneId.of(properties.gameTimezone)),
        )
        if (rewardAvailable) primary += InlineKeyboardButton(GameI18n.t(language, "daily"), "nav:daily")
        rows += primary
        rows += listOf(
            InlineKeyboardButton(GameI18n.t(language, "army"), "nav:army"),
            InlineKeyboardButton(GameI18n.t(language, "shop"), "nav:shop"),
        )
        rows += listOf(
            InlineKeyboardButton(GameI18n.t(language, "upgrade"), "nav:upgrade"),
            InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"),
        )
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front"), "nav:front"))
        rows += listOf(
            InlineKeyboardButton(frontLocalized(language, "🏆 Ratings", "🏆 Рейтинги"), "nav:rankings"),
            InlineKeyboardButton(frontLocalized(language, "📖 Guide", "📖 Гайд"), "nav:guide"),
        )
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "settings"), "nav:settings"))
        return InlineKeyboardMarkup(rows)
    }

    private fun settings(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        telegram.sendMessage(chatId, GameI18n.t(language, "settings_text"), actionKeyboard(telegramId, language))
    }

    private fun languageMenu(telegramId: Long, chatId: Long, prefix: String? = null) {
        val current = language(telegramId)
        val text = listOfNotNull(
            prefix,
            GameI18n.t(current, "language_title"),
            GameI18n.t(current, "language_choose", current.nativeName),
        ).joinToString("\n\n")
        val keyboard = InlineKeyboardMarkup(GameLanguage.entries.chunked(2).map { row ->
            row.map { option ->
                val checked = if (option == current) " ✅" else ""
                InlineKeyboardButton("${option.flag} ${option.nativeName}$checked", "language:${option.code}")
            }
        })
        telegram.sendMessage(chatId, text, keyboard)
    }

    private fun selectLanguage(telegramId: Long, chatId: Long, code: String) {
        val selected = GameLanguage.entries.firstOrNull { it.code == code } ?: return languageMenu(telegramId, chatId)
        jdbc.sql("UPDATE players SET language = :language, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id")
            .param("language", selected.code).param("id", telegramId).update()
        languageMenu(telegramId, chatId, GameI18n.t(selected, "language_changed", selected.nativeName))
    }

    private fun nickname(telegramId: Long, chatId: Long, argument: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (argument.isBlank()) {
            telegram.sendMessage(chatId, GameI18n.t(language, "nickname_prompt", player.nickname ?: GameI18n.t(language, "not_set")))
            return
        }
        val validated = nicknamePolicy.validate(argument)
        if (validated !is NicknameValidation.Valid) {
            telegram.sendMessage(chatId, GameI18n.t(language, "nickname_invalid"))
            return
        }
        if (validated.value == player.nickname) {
            telegram.sendMessage(chatId, GameI18n.t(language, "nickname_same"))
            return
        }
        jdbc.sql("UPDATE players SET pending_nickname = :nickname, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id")
            .param("nickname", validated.value).param("id", telegramId).update()
        val current = player.nickname ?: player.firstName
        telegram.sendMessage(
            chatId,
            GameI18n.t(language, "nickname_confirm", current, validated.value),
            InlineKeyboardMarkup(listOf(listOf(
                InlineKeyboardButton(GameI18n.t(language, "yes"), "nickname:confirm"),
                InlineKeyboardButton(GameI18n.t(language, "no"), "nickname:cancel"),
            ))),
        )
    }

    private fun confirmNickname(telegramId: Long, chatId: Long) {
        val before = player(telegramId)
        val language = GameLanguage.fromStored(before.language)
        val pending = before.pendingNickname ?: return telegram.sendMessage(chatId, GameI18n.t(language, "nickname_invalid"))
        val validated = nicknamePolicy.validate(pending)
        if (validated !is NicknameValidation.Valid) return telegram.sendMessage(chatId, GameI18n.t(language, "nickname_invalid"))
        val updated = jdbc.sql(
            "UPDATE players SET nickname = :nickname, pending_nickname = NULL, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND pending_nickname = :pending",
        ).param("nickname", validated.value).param("pending", pending).param("id", telegramId).update()
        if (updated > 0) telegram.sendMessage(chatId, GameI18n.t(language, "nickname_changed", validated.value), actionKeyboard(telegramId, language))
    }

    private fun cancelNickname(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        jdbc.sql("UPDATE players SET pending_nickname = NULL, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id")
            .param("id", telegramId).update()
        telegram.sendMessage(chatId, GameI18n.t(language, "nickname_cancelled"), actionKeyboard(telegramId, language))
    }

    private fun countryMenu(telegramId: Long, chatId: Long, query: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        player.allianceCode?.let {
            telegram.sendMessage(chatId, GameI18n.t(language, "country_locked", AllianceCatalog.option(it, language).label), actionKeyboard(telegramId, language))
            return
        }
        if (query.isBlank()) {
            val text = "${GameI18n.t(language, "country_title")}\n\n${GameI18n.t(language, "country_recommended")}\n${GameI18n.t(language, "country_search")}"
            telegram.sendMessage(chatId, text, recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            return
        }
        val results = AllianceCatalog.search(query, language)
        if (results.isEmpty()) {
            telegram.sendMessage(chatId, GameI18n.t(language, "country_none"), recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            return
        }
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "country_results", query)}\n${GameI18n.t(language, "country_search")}",
            countryOptionsKeyboard(results),
        )
    }

    private fun countryPage(telegramId: Long, chatId: Long, requestedPage: Int) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode != null) return countryMenu(telegramId, chatId, "")
        val countries = AllianceCatalog.sorted(language)
        val totalPages = (countries.size + COUNTRY_PAGE_SIZE - 1) / COUNTRY_PAGE_SIZE
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val options = countries.drop(page * COUNTRY_PAGE_SIZE).take(COUNTRY_PAGE_SIZE)
        val navigation = buildList {
            if (page > 0) add(InlineKeyboardButton(GameI18n.t(language, "previous"), "country:page:${page - 1}"))
            if (page + 1 < totalPages) add(InlineKeyboardButton(GameI18n.t(language, "next"), "country:page:${page + 1}"))
        }
        val rows = options.map { listOf(InlineKeyboardButton(it.label, "country:select:${it.code}")) }.toMutableList()
        if (navigation.isNotEmpty()) rows += navigation
        telegram.sendMessage(chatId, GameI18n.t(language, "country_page", page + 1, totalPages), InlineKeyboardMarkup(rows))
    }

    private fun countryOptionsKeyboard(options: List<AllianceOption>) = InlineKeyboardMarkup(
        options.map { listOf(InlineKeyboardButton(it.label, "country:select:${it.code}")) },
    )

    private fun language(telegramId: Long) = GameLanguage.fromStored(player(telegramId).language)

    private fun languageByChat(chatId: Long): GameLanguage = jdbc.sql("SELECT language FROM players WHERE telegram_id = :id")
        .param("id", chatId).query(String::class.java).optional()
        .map(GameLanguage::fromStored).orElse(GameLanguage.EN)

    private fun spatialHighlights(
        language: GameLanguage,
        events: List<SpatialBattleEvent>,
        map: BattleMapDefinition,
    ): String = events.filter {
        it.type in setOf(SpatialEventType.OBJECTIVE_CAPTURED, SpatialEventType.UNIT_DESTROYED, SpatialEventType.UNIT_ROUTED)
    }.takeLast(4).joinToString("\n") { event ->
        val side = GameI18n.t(language, if (event.side == BattleSide.PLAYER) "player_side" else "enemy_side")
        val text = when (event.type) {
            SpatialEventType.OBJECTIVE_CAPTURED -> {
                val objective = map.objectives.first { it.id == event.objectiveId }
                GameI18n.t(language, "event_objective_captured", side, GameI18n.t(language, objective.nameKey))
            }
            SpatialEventType.UNIT_DESTROYED -> {
                val unit = event.targetUnitCode?.let(equipment::get)?.name(language) ?: event.targetUnitCode.orEmpty()
                GameI18n.t(language, "event_unit_destroyed", side, unit)
            }
            SpatialEventType.UNIT_ROUTED -> {
                val unit = event.unitCode?.let(equipment::get)?.name(language) ?: event.unitCode.orEmpty()
                GameI18n.t(language, "event_unit_routed", side, unit)
            }
            else -> ""
        }
        "${GameI18n.t(language, "step")} ${event.step}: $text"
    }

    private fun endReasonKey(reason: SpatialEndReason): String = when (reason) {
        SpatialEndReason.ALL_OBJECTIVES_CAPTURED -> "end_all_objectives"
        SpatialEndReason.ARMY_DESTROYED -> "end_army_destroyed"
        SpatialEndReason.ARMY_ROUTED -> "end_army_routed"
    }

    private fun localizedIntel(language: GameLanguage, offer: OperationOffer): String = when (offer.difficulty) {
        com.tggames.frontline.battle.Difficulty.SCOUTED -> GameI18n.enemyIntel(language, offer.enemy)
        com.tggames.frontline.battle.Difficulty.STANDARD -> "${GameI18n.t(language, "likely")}: ${GameI18n.enemy(language, offer.enemy)}"
        com.tggames.frontline.battle.Difficulty.RISKY -> GameI18n.t(language, "unknown_composition")
    }

    private fun todayKey(): String = LocalDate.now(ZoneId.of(properties.gameTimezone)).toString()

    private fun helpText(language: GameLanguage) = buildString {
        appendLine(GameI18n.t(language, "help"))
        appendLine("/army — ${GameI18n.t(language, "army_title")}")
        appendLine("/shop — ${GameI18n.t(language, "shop_title")}")
        appendLine("/upgrade — ${GameI18n.t(language, "upgrade_title")}")
        appendLine("/daily — ${GameI18n.t(language, "daily")}")
        appendLine("/rankings — ${frontLocalized(language, "alliance and player ratings", "рейтинг стран и игроков")}")
        append("/guide — ${frontLocalized(language, "game rules by section", "правила игры по разделам")}")
    }

    private fun frontLocalized(language: GameLanguage, english: String, russian: String): String =
        if (language == GameLanguage.RU) russian else english

    companion object {
        private const val COUNTRY_PAGE_SIZE = 10
        private const val ALLIANCE_RATING_PAGE_SIZE = 10
    }

}

data class Player(
    val telegramId: Long,
    val firstName: String,
    val nickname: String?,
    val pendingNickname: String?,
    val language: String,
    val telegramLanguage: String?,
    val allianceCode: String?,
    val commanderLevel: Int,
    val xp: Long,
    val credits: Long,
    val materials: Long,
    val commandCapacity: Int,
    val battleOfferVersion: Long,
    val victories: Int,
    val defeats: Int,
    val currentStreak: Int,
    val bestStreak: Int,
    val dailyRewardStreak: Int,
    val dailyRewardLastClaim: LocalDate?,
    val dailyRewardClaims: Long,
) {
    val displayName: String get() = nickname?.takeIf(String::isNotBlank) ?: firstName
}

private data class Selection(val expectedOfferVersion: Long, val slot: Int)
private data class GroupBinding(val presetNo: Int, val version: Int)
