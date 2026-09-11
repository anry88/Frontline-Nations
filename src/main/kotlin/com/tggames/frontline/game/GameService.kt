package com.tggames.frontline.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleEngine
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.campaign.CampaignService
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
import com.tggames.frontline.telegram.TelegramCallbackQuery
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramUpdate
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
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
) {
    @Transactional
    fun handle(update: TelegramUpdate) {
        if (!claimUpdate(update.updateId)) return

        update.callbackQuery?.let { callback ->
            ensurePlayer(callback.from.id, callback.from.firstName, callback.from.languageCode)
            handleCallback(callback)
            telegram.answerCallback(callback.id)
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
            "/profile" -> telegram.sendMessage(message.chat.id, profileText(user.id), actionKeyboard(language(user.id)))
            "/front" -> front(user.id, user.firstName, message.chat.id)
            "/contribute" -> contribute(user.id, user.firstName, message.chat.id, argument)
            "/language" -> if (argument.isBlank()) languageMenu(user.id, message.chat.id) else selectLanguage(user.id, message.chat.id, argument.lowercase())
            "/nickname" -> nickname(user.id, message.chat.id, argument)
            "/country" -> countryMenu(user.id, message.chat.id, argument)
            "/settings" -> settings(user.id, message.chat.id)
            "/help", "" -> telegram.sendMessage(message.chat.id, helpText(language(user.id)), actionKeyboard(language(user.id)))
            else -> telegram.sendMessage(message.chat.id, "${GameI18n.t(language(user.id), "unknown")}\n\n${helpText(language(user.id))}", actionKeyboard(language(user.id)))
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
            data == "nav:profile" -> telegram.sendMessage(chatId, profileText(callback.from.id), actionKeyboard(language))
            data == "nav:front" -> front(callback.from.id, callback.from.firstName, chatId)
            data == "nav:settings" -> settings(callback.from.id, chatId)
            data.startsWith("op:") -> showTactics(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("fight:") -> resolveBattle(callback.from.id, callback.from.firstName, chatId, data)
            else -> telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(language))
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
            telegram.sendMessage(chatId, "${GameI18n.t(language, "welcome_back", current.displayName)}\n\n${profileText(telegramId)}", actionKeyboard(language))
            return
        }
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "welcome", firstName)}\n\n${GameI18n.t(language, "country_neutral")}",
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
            telegram.sendMessage(chatId, GameI18n.t(language, "country_locked", current), actionKeyboard(language))
        } else {
            campaigns.ensureCurrentWeek()
            telegram.sendMessage(chatId, "${GameI18n.t(language, "country_selected", selected)}\n\n${profileText(telegramId)}", actionKeyboard(language))
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
        if (player.combatOrders <= 0) {
            telegram.sendMessage(
                chatId,
                GameI18n.t(language, "no_orders"),
                actionKeyboard(language, includeBattle = false),
            )
            return
        }

        val offers = offersFor(telegramId, player.combatOrders)
        val text = buildString {
            appendLine(GameI18n.t(language, "operations"))
            appendLine()
            offers.forEachIndexed { index, offer ->
                appendLine("${index + 1}. ${offer.difficulty.icon} ${GameI18n.battlefield(language, offer.battlefield.location)}")
                appendLine("   ${GameI18n.biome(language, offer.battlefield.biome)} · ${GameI18n.difficulty(language, offer.difficulty)} · ${GameI18n.t(language, "reward")} ${offer.difficulty.rewardPercent}%")
                appendLine("   ${GameI18n.t(language, "intel")} (${GameI18n.intelLevel(language, offer.difficulty)}): ${localizedIntel(language, offer)}")
            }
            appendLine()
            append(GameI18n.t(language, "orders_choose", player.combatOrders))
        }
        val keyboard = InlineKeyboardMarkup(
            offers.mapIndexed { index, offer ->
                listOf(InlineKeyboardButton("${index + 1}. ${offer.difficulty.icon} ${GameI18n.battlefield(language, offer.battlefield.location)}", "op:${player.combatOrders}:${offer.slot}"))
            } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"))),
        )
        telegram.sendMessage(chatId, text, keyboard)
    }

    private fun showTactics(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parsed = parseSelection(callbackData, "op") ?: return staleSelection(chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.combatOrders != parsed.expectedOrders) return staleSelection(chatId)
        val operation = offersFor(telegramId, parsed.expectedOrders).getOrNull(parsed.slot) ?: return staleSelection(chatId)

        val text = buildString {
            appendLine("🎯 ${GameI18n.battlefield(language, operation.battlefield.location)}")
            appendLine()
            appendLine("${GameI18n.t(language, "terrain")}: ${GameI18n.biome(language, operation.battlefield.biome)}")
            appendLine("${GameI18n.t(language, "risk")}: ${GameI18n.difficulty(language, operation.difficulty)}")
            appendLine("${GameI18n.t(language, "reward")}: ${operation.difficulty.rewardPercent}%")
            appendLine("${GameI18n.t(language, "intel")} (${GameI18n.intelLevel(language, operation.difficulty)}): ${localizedIntel(language, operation)}")
            appendLine()
            appendLine(GameI18n.t(language, "choose_tactic"))
            Tactic.entries.forEach { appendLine("${it.icon} ${GameI18n.tactic(language, it)} — ${GameI18n.tacticHint(language, it)}") }
        }
        val buttons = Tactic.entries.map { tactic ->
            InlineKeyboardButton(
                "${tactic.icon} ${GameI18n.tactic(language, tactic)}",
                "fight:${parsed.expectedOrders}:${parsed.slot}:${tactic.code}",
            )
        }.chunked(2) + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "other_operations"), "nav:battle")))
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun resolveBattle(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 4 || parts[0] != "fight") return staleSelection(chatId)
        val expectedOrders = parts[1].toIntOrNull() ?: return staleSelection(chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(chatId)
        val tactic = Tactic.fromCode(parts[3]) ?: return staleSelection(chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.combatOrders != expectedOrders) return staleSelection(chatId)
        val operation = offersFor(telegramId, expectedOrders).getOrNull(slot) ?: return staleSelection(chatId)
        val battleId = UUID.randomUUID()
        val battle = battleEngine.resolve(
            properties.battleServerSalt,
            "$telegramId:${todayKey()}:$expectedOrders:$slot",
            player.commanderLevel,
            operation,
            tactic,
        )

        val updated = jdbc.sql(
            """
            UPDATE players
               SET combat_orders = combat_orders - 1,
                   xp = xp + :xp,
                   credits = credits + :credits,
                   research_points = research_points + :research,
                   materials = materials + :materials,
                   commander_level = LEAST(50, 1 + CAST((xp + :xp) / 1000 AS INTEGER)),
                   victories = victories + CASE WHEN :victory THEN 1 ELSE 0 END,
                   defeats = defeats + CASE WHEN :victory THEN 0 ELSE 1 END,
                   current_streak = CASE WHEN :victory THEN current_streak + 1 ELSE 0 END,
                   best_streak = CASE WHEN :victory THEN GREATEST(best_streak, current_streak + 1) ELSE best_streak END,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id AND combat_orders = :expectedOrders
            """.trimIndent(),
        ).param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("research", battle.researchPoints)
            .param("materials", battle.materials)
            .param("victory", battle.victory)
            .param("id", telegramId)
            .param("expectedOrders", expectedOrders)
            .update()

        if (updated == 0) return staleSelection(chatId)

        jdbc.sql(
            """
            INSERT INTO battles(
                id, player_telegram_id, victory, player_power, enemy_power,
                xp_reward, credits_reward, research_points_reward, materials_reward,
                battle_seed, commander_level_snapshot, seed_hash, engine_version, location, biome, difficulty,
                enemy_archetype, tactic, rounds, events_json
            )
            VALUES (
                :id, :playerId, :victory, :playerPower, :enemyPower,
                :xp, :credits, :research, :materials,
                :battleSeed, :commanderLevel, :seedHash, 2, :location, :biome, :difficulty,
                :enemy, :tactic, :rounds, CAST(:events AS jsonb)
            )
            """.trimIndent(),
        ).param("id", battleId)
            .param("playerId", telegramId)
            .param("victory", battle.victory)
            .param("playerPower", battle.playerPower)
            .param("enemyPower", battle.enemyPower)
            .param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("research", battle.researchPoints)
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
            .update()

        recordWalletChange(telegramId, "XP", battle.xp.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "CREDITS", battle.credits.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "RESEARCH_POINTS", battle.researchPoints.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "MATERIALS", battle.materials.toLong(), "BATTLE_REWARD", battleId)

        val fresh = player(telegramId)
        val highlights = listOf(0, battle.events.size / 2, battle.events.lastIndex).distinct()
            .joinToString("\n") { index ->
                val event = battle.events[index]
                localizedEvent(language, event.round, battle.events.size, event.playerScore >= event.enemyScore, index == battle.events.lastIndex, battle.victory, tactic, operation)
            }
        val outcome = GameI18n.t(language, if (battle.victory) "victory" else "withdrawal")
        val tacticImpact = signed(battle.tacticBonus)
        val terrainImpact = signed(battle.terrainBonus)
        val levelUp = if (fresh.commanderLevel > player.commanderLevel) "\n⭐ ${GameI18n.t(language, "new_level")}: ${fresh.commanderLevel}" else ""
        val report = buildString {
            appendLine(GameI18n.t(language, "battle_complete"))
            appendLine("${GameI18n.battlefield(language, operation.battlefield.location)} · ${GameI18n.biome(language, operation.battlefield.biome)}")
            appendLine("${tactic.icon} ${GameI18n.tactic(language, tactic)}")
            appendLine("${GameI18n.t(language, "enemy_label")}: ${GameI18n.enemy(language, operation.enemy)}")
            appendLine()
            appendLine(outcome)
            appendLine("${GameI18n.t(language, "final_power")}: ${battle.playerPower} : ${battle.enemyPower}")
            appendLine("${GameI18n.t(language, "counter_order")}: $tacticImpact · ${GameI18n.t(language, "terrain")}: $terrainImpact")
            appendLine()
            appendLine(highlights)
            appendLine()
            appendLine("+${battle.xp} XP")
            appendLine("+${battle.credits} Credits")
            appendLine("+${battle.researchPoints} Research Points")
            appendLine("+${battle.materials} Materials")
            appendLine("${GameI18n.t(language, "streak")}: ${fresh.currentStreak}$levelUp")
            appendLine()
            append("${GameI18n.t(language, "combat_orders")}: ${fresh.combatOrders}/5")
        }
        telegram.sendMessage(chatId, report, actionKeyboard(language, includeBattle = fresh.combatOrders > 0))
    }

    private fun staleSelection(chatId: Long) {
        val language = languageByChat(chatId)
        telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(language))
    }

    private fun offersFor(telegramId: Long, orders: Int): List<OperationOffer> =
        battleEngine.offers(properties.battleServerSalt, "$telegramId:${todayKey()}:$orders")

    private fun parseSelection(data: String, prefix: String): Selection? {
        val parts = data.split(':')
        if (parts.size != 3 || parts[0] != prefix) return null
        return Selection(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
    }

    private fun contribute(telegramId: Long, firstName: String, chatId: Long, argument: String) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, GameI18n.t(language, "choose_country"), recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            return
        }
        val amount = argument.toIntOrNull() ?: 50
        if (amount !in 10..10_000) {
            telegram.sendMessage(chatId, GameI18n.t(language, "contribute_usage"))
            return
        }
        val outcome = campaigns.contribute(telegramId, player.allianceCode, amount, language)
        val front = campaigns.frontText(player.allianceCode, language)
        telegram.sendMessage(chatId, "${outcome.message}\n\n$front", actionKeyboard(language))
    }

    private fun profileText(telegramId: Long, firstName: String? = null): String {
        if (firstName != null) ensurePlayer(telegramId, firstName)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode?.let { AllianceCatalog.option(it, language).label } ?: GameI18n.t(language, "not_selected")
        val battles = p.victories + p.defeats
        val winRate = if (battles == 0) 0 else p.victories * 100 / battles
        val levelProgress = if (p.commanderLevel == 50) "MAX" else "${p.xp % 1000}/1000 XP"
        return """
            🪖 ${GameI18n.t(language, "commander")} ${p.displayName}

            ${GameI18n.t(language, "alliance")}: $alliance
            ${GameI18n.t(language, "level")}: ${p.commanderLevel} · $levelProgress
            ${GameI18n.t(language, "victories")}: ${p.victories}/$battles · $winRate%
            ${GameI18n.t(language, "streak")}: ${p.currentStreak} · ${GameI18n.t(language, "record")} ${p.bestStreak}

            Credits: ${p.credits}
            Research Points: ${p.researchPoints}
            Materials: ${p.materials}
            ${GameI18n.t(language, "combat_orders")}: ${p.combatOrders}/5
        """.trimIndent()
    }

    private fun front(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        telegram.sendMessage(chatId, campaigns.frontText(player.allianceCode, language), actionKeyboard(language))
    }

    private fun ensurePlayer(telegramId: Long, firstName: String, telegramLanguage: String? = null) {
        val inferred = GameLanguage.fromTelegram(telegramLanguage).code
        jdbc.sql(
            """
            INSERT INTO players(telegram_id, first_name, language, telegram_language)
            VALUES (:id, :firstName, :language, :telegramLanguage)
            ON CONFLICT (telegram_id) DO UPDATE
               SET first_name = EXCLUDED.first_name,
                   telegram_language = COALESCE(EXCLUDED.telegram_language, players.telegram_language),
                   updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("id", telegramId)
            .param("firstName", firstName.take(128))
            .param("language", inferred)
            .param("telegramLanguage", telegramLanguage?.take(16))
            .update()
    }

    private fun player(telegramId: Long): Player = jdbc.sql(
        """
        SELECT telegram_id, first_name, nickname, pending_nickname, language, telegram_language,
               alliance_code, commander_level, xp,
               credits, research_points, materials, combat_orders,
               victories, defeats, current_streak, best_streak
          FROM players
         WHERE telegram_id = :id
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

    private fun actionKeyboard(language: GameLanguage, includeBattle: Boolean = true): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (includeBattle) rows += listOf(InlineKeyboardButton(GameI18n.t(language, "battle"), "nav:battle"))
        rows += listOf(
            InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"),
            InlineKeyboardButton(GameI18n.t(language, "front"), "nav:front"),
        )
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "settings"), "nav:settings"))
        return InlineKeyboardMarkup(rows)
    }

    private fun settings(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        telegram.sendMessage(chatId, GameI18n.t(language, "settings_text"), actionKeyboard(language))
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
        if (updated > 0) telegram.sendMessage(chatId, GameI18n.t(language, "nickname_changed", validated.value), actionKeyboard(language))
    }

    private fun cancelNickname(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        jdbc.sql("UPDATE players SET pending_nickname = NULL, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id")
            .param("id", telegramId).update()
        telegram.sendMessage(chatId, GameI18n.t(language, "nickname_cancelled"), actionKeyboard(language))
    }

    private fun countryMenu(telegramId: Long, chatId: Long, query: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        player.allianceCode?.let {
            telegram.sendMessage(chatId, GameI18n.t(language, "country_locked", AllianceCatalog.option(it, language).label), actionKeyboard(language))
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

    private fun localizedIntel(language: GameLanguage, offer: OperationOffer): String = when (offer.difficulty) {
        com.tggames.frontline.battle.Difficulty.SCOUTED -> GameI18n.enemyIntel(language, offer.enemy)
        com.tggames.frontline.battle.Difficulty.STANDARD -> "${GameI18n.t(language, "likely")}: ${GameI18n.enemy(language, offer.enemy)}"
        com.tggames.frontline.battle.Difficulty.RISKY -> GameI18n.t(language, "unknown_composition")
    }

    private fun localizedEvent(
        language: GameLanguage,
        round: Int,
        rounds: Int,
        wonRound: Boolean,
        final: Boolean,
        victory: Boolean,
        tactic: Tactic,
        operation: OperationOffer,
    ): String {
        val text = when {
            final -> GameI18n.t(language, if (victory) "objective_secured" else "unit_withdrew")
            round == 1 && wonRound -> "${GameI18n.tactic(language, tactic)}: ${GameI18n.t(language, "initiative_seized")}"
            round == 1 -> "${GameI18n.enemy(language, operation.enemy)}: ${GameI18n.t(language, "initiative_seized")}"
            wonRound -> GameI18n.t(language, "enemy_suppressed")
            else -> GameI18n.t(language, "advance_slowed")
        }
        return "${GameI18n.t(language, "round")} $round/$rounds: $text"
    }

    private fun todayKey(): String = LocalDate.now(ZoneId.of(properties.gameTimezone)).toString()

    private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    private fun helpText(language: GameLanguage) = GameI18n.t(language, "help")

    companion object {
        private const val COUNTRY_PAGE_SIZE = 10
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "\${frontline.game-timezone}")
    fun resetDailyOrders() {
        jdbc.sql("UPDATE players SET combat_orders = 5, updated_at = CURRENT_TIMESTAMP").update()
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
    val researchPoints: Long,
    val materials: Long,
    val combatOrders: Int,
    val victories: Int,
    val defeats: Int,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    val displayName: String get() = nickname?.takeIf(String::isNotBlank) ?: firstName
}

private data class Selection(val expectedOrders: Int, val slot: Int)
