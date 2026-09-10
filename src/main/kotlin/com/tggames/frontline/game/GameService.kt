package com.tggames.frontline.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleEngine
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.config.FrontlineProperties
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
import java.time.temporal.WeekFields
import java.util.UUID

@Service
class GameService(
    private val jdbc: JdbcClient,
    private val telegram: TelegramClient,
    private val battleEngine: BattleEngine,
    private val properties: FrontlineProperties,
    private val objectMapper: ObjectMapper,
) {
    private val alliances = linkedMapOf(
        "RS" to "🇷🇸 Сербия",
        "BR" to "🇧🇷 Бразилия",
        "IN" to "🇮🇳 Индия",
        "JP" to "🇯🇵 Япония",
        "EG" to "🇪🇬 Египет",
        "CA" to "🇨🇦 Канада",
        "XK" to "🇽🇰 Косово",
        "PS" to "🇵🇸 Палестина",
    )

    @Transactional
    fun handle(update: TelegramUpdate) {
        if (!claimUpdate(update.updateId)) return

        update.callbackQuery?.let { callback ->
            handleCallback(callback)
            telegram.answerCallback(callback.id)
            return
        }

        val message = update.message ?: return
        val user = message.from ?: return
        val command = message.text.orEmpty().trim().substringBefore('@').substringBefore(' ').lowercase()
        val argument = message.text.orEmpty().trim().substringAfter(' ', "").trim()

        when (command) {
            "/start" -> start(user.id, user.firstName, message.chat.id)
            "/battle" -> battleMenu(user.id, user.firstName, message.chat.id)
            "/profile" -> telegram.sendMessage(message.chat.id, profileText(user.id, user.firstName), actionKeyboard())
            "/front" -> telegram.sendMessage(message.chat.id, frontText(), actionKeyboard())
            "/contribute" -> contribute(user.id, user.firstName, message.chat.id, argument)
            "/help", "" -> telegram.sendMessage(message.chat.id, helpText(), actionKeyboard())
            else -> telegram.sendMessage(message.chat.id, "Неизвестная команда.\n\n${helpText()}", actionKeyboard())
        }
    }

    private fun handleCallback(callback: TelegramCallbackQuery) {
        val chatId = callback.message?.chat?.id ?: callback.from.id
        val data = callback.data.orEmpty()
        when {
            data.startsWith("alliance:") -> {
                selectAlliance(callback.from.id, callback.from.firstName, data.substringAfter(':'))
                telegram.sendMessage(chatId, profileText(callback.from.id), actionKeyboard())
            }
            data == "nav:battle" -> battleMenu(callback.from.id, callback.from.firstName, chatId)
            data == "nav:profile" -> telegram.sendMessage(chatId, profileText(callback.from.id, callback.from.firstName), actionKeyboard())
            data == "nav:front" -> telegram.sendMessage(chatId, frontText(), actionKeyboard())
            data.startsWith("op:") -> showTactics(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("fight:") -> resolveBattle(callback.from.id, callback.from.firstName, chatId, data)
            else -> telegram.sendMessage(chatId, "Эта кнопка устарела. Откройте свежий список операций.", actionKeyboard())
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
        ensurePlayer(telegramId, firstName)
        val current = player(telegramId)
        if (current.allianceCode != null) {
            telegram.sendMessage(chatId, "С возвращением, ${current.firstName}!\n\n${profileText(telegramId)}", actionKeyboard())
            return
        }
        telegram.sendMessage(
            chatId,
            "Добро пожаловать в Frontline Nations, $firstName!\n\nВыберите альянс. Это игровая сторона, а не политическое заявление.",
            allianceKeyboard(),
        )
    }

    private fun selectAlliance(telegramId: Long, firstName: String, code: String) {
        val alliance = alliances[code] ?: return
        ensurePlayer(telegramId, firstName)
        jdbc.sql("UPDATE players SET alliance_code = :code, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND alliance_code IS NULL")
            .param("code", code)
            .param("id", telegramId)
            .update()
        check(alliance.isNotBlank())
    }

    private fun battleMenu(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, "Сначала выберите альянс:", allianceKeyboard())
            return
        }
        if (player.combatOrders <= 0) {
            telegram.sendMessage(
                chatId,
                "Боевые приказы закончились. Следующее пополнение — в 00:00 по игровому времени.",
                actionKeyboard(includeBattle = false),
            )
            return
        }

        val offers = offersFor(telegramId, player.combatOrders)
        val text = buildString {
            appendLine("🗺 ДОСТУПНЫЕ ОПЕРАЦИИ")
            appendLine()
            offers.forEachIndexed { index, offer ->
                appendLine("${index + 1}. ${offer.difficulty.icon} ${offer.battlefield.location}")
                appendLine("   ${offer.battlefield.biome} · ${offer.difficulty.title.lowercase()} · награда ${offer.difficulty.rewardPercent}%")
                appendLine("   Разведка (${offer.difficulty.intelLevel}): ${offer.intel()}")
            }
            appendLine()
            append("Приказов: ${player.combatOrders}/5. Выберите операцию — приказ спишется только после выбора тактики.")
        }
        val keyboard = InlineKeyboardMarkup(
            offers.mapIndexed { index, offer ->
                listOf(InlineKeyboardButton("${index + 1}. ${offer.title}", "op:${player.combatOrders}:${offer.slot}"))
            } + listOf(listOf(InlineKeyboardButton("🪖 Профиль", "nav:profile"))),
        )
        telegram.sendMessage(chatId, text, keyboard)
    }

    private fun showTactics(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parsed = parseSelection(callbackData, "op") ?: return staleSelection(chatId)
        val player = player(telegramId)
        if (player.allianceCode == null || player.combatOrders != parsed.expectedOrders) return staleSelection(chatId)
        val operation = offersFor(telegramId, parsed.expectedOrders).getOrNull(parsed.slot) ?: return staleSelection(chatId)

        val text = buildString {
            appendLine("🎯 ${operation.battlefield.location}")
            appendLine()
            appendLine("Местность: ${operation.battlefield.biome}")
            appendLine("Риск: ${operation.difficulty.title}")
            appendLine("Награда: ${operation.difficulty.rewardPercent}%")
            appendLine("Разведданные (${operation.difficulty.intelLevel}): ${operation.intel()}")
            appendLine()
            appendLine("Выберите тактику. Контрприказ и местность могут перевесить разницу в силе:")
            Tactic.entries.forEach { appendLine("${it.icon} ${it.title} — ${it.hint}") }
        }
        val buttons = Tactic.entries.map { tactic ->
            InlineKeyboardButton(
                "${tactic.icon} ${tactic.title}",
                "fight:${parsed.expectedOrders}:${parsed.slot}:${tactic.code}",
            )
        }.chunked(2) + listOf(listOf(InlineKeyboardButton("↩️ Другие операции", "nav:battle")))
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
                "Раунд ${event.round}: ${event.text}"
            }
        val outcome = if (battle.victory) "🏆 ПОБЕДА" else "↩️ ОРГАНИЗОВАННОЕ ОТСТУПЛЕНИЕ"
        val tacticImpact = signed(battle.tacticBonus)
        val terrainImpact = signed(battle.terrainBonus)
        val levelUp = if (fresh.commanderLevel > player.commanderLevel) "\n⭐ Новый уровень командира: ${fresh.commanderLevel}" else ""
        val report = buildString {
            appendLine("⚔ ОПЕРАЦИЯ ЗАВЕРШЕНА")
            appendLine("${operation.battlefield.location} · ${operation.battlefield.biome}")
            appendLine("${tactic.icon} Приказ: ${tactic.title}")
            appendLine("Противник: ${operation.enemy.title}")
            appendLine()
            appendLine(outcome)
            appendLine("Итоговая сила: ${battle.playerPower} : ${battle.enemyPower}")
            appendLine("Контрприказ: $tacticImpact · местность: $terrainImpact")
            appendLine()
            appendLine(highlights)
            appendLine()
            appendLine("+${battle.xp} XP")
            appendLine("+${battle.credits} Credits")
            appendLine("+${battle.researchPoints} Research Points")
            appendLine("+${battle.materials} Materials")
            appendLine("Серия побед: ${fresh.currentStreak}$levelUp")
            appendLine()
            append("Осталось приказов: ${fresh.combatOrders}/5")
        }
        telegram.sendMessage(chatId, report, actionKeyboard(includeBattle = fresh.combatOrders > 0))
    }

    private fun staleSelection(chatId: Long) {
        telegram.sendMessage(chatId, "Предложение устарело или приказ уже использован. Обновите список операций.", actionKeyboard())
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
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, "Сначала выберите альянс через /start.")
            return
        }
        val amount = argument.toIntOrNull() ?: 50
        if (amount !in 10..10_000) {
            telegram.sendMessage(chatId, "Укажите вклад от 10 до 10000 Credits: /contribute 100")
            return
        }
        val debited = jdbc.sql(
            "UPDATE players SET credits = credits - :amount, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id AND credits >= :amount",
        ).param("amount", amount).param("id", telegramId).update()
        if (debited == 0) {
            telegram.sendMessage(chatId, "Недостаточно Credits. Текущий баланс: ${player.credits}.")
            return
        }
        jdbc.sql(
            """
            INSERT INTO campaign_contributions(week_key, player_telegram_id, alliance_code, credits, power)
            VALUES (:week, :playerId, :alliance, :amount, :power)
            """.trimIndent(),
        ).param("week", weekKey())
            .param("playerId", telegramId)
            .param("alliance", player.allianceCode)
            .param("amount", amount)
            .param("power", amount)
            .update()
        recordWalletChange(telegramId, "CREDITS", -amount.toLong(), "CAMPAIGN_CONTRIBUTION", null)
        telegram.sendMessage(chatId, "Вклад принят: $amount Credits → $amount силы фронта.\n\n${frontText()}", actionKeyboard())
    }

    private fun profileText(telegramId: Long, firstName: String? = null): String {
        if (firstName != null) ensurePlayer(telegramId, firstName)
        val p = player(telegramId)
        val alliance = p.allianceCode?.let { alliances[it] } ?: "не выбран"
        val battles = p.victories + p.defeats
        val winRate = if (battles == 0) 0 else p.victories * 100 / battles
        val levelProgress = if (p.commanderLevel == 50) "MAX" else "${p.xp % 1000}/1000 XP"
        return """
            🪖 КОМАНДИР ${p.firstName}

            Альянс: $alliance
            Уровень: ${p.commanderLevel} · $levelProgress
            Победы: ${p.victories}/$battles · $winRate%
            Серия: ${p.currentStreak} · рекорд ${p.bestStreak}

            Credits: ${p.credits}
            Research Points: ${p.researchPoints}
            Materials: ${p.materials}
            Боевые приказы: ${p.combatOrders}/5
        """.trimIndent()
    }

    private fun frontText(): String {
        val rows = jdbc.sql(
            """
            SELECT alliance_code, COALESCE(SUM(power), 0) AS total_power
              FROM campaign_contributions
             WHERE week_key = :week
             GROUP BY alliance_code
             ORDER BY total_power DESC, alliance_code
            """.trimIndent(),
        ).param("week", weekKey())
            .query { rs, _ -> (alliances[rs.getString("alliance_code")] ?: rs.getString("alliance_code")) to rs.getLong("total_power") }
            .list()
        val standings = if (rows.isEmpty()) "Вкладов пока нет." else rows.joinToString("\n") { "${it.first}: ${it.second}" }
        return "🌍 ФРОНТ ${weekKey()}\n\n$standings\n\nВнести ресурсы: /contribute 100"
    }

    private fun ensurePlayer(telegramId: Long, firstName: String) {
        jdbc.sql(
            """
            INSERT INTO players(telegram_id, first_name)
            VALUES (:id, :firstName)
            ON CONFLICT (telegram_id) DO UPDATE SET first_name = EXCLUDED.first_name, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).param("id", telegramId).param("firstName", firstName.take(128)).update()
    }

    private fun player(telegramId: Long): Player = jdbc.sql(
        """
        SELECT telegram_id, first_name, alliance_code, commander_level, xp,
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

    private fun allianceKeyboard() = InlineKeyboardMarkup(
        alliances.entries.chunked(2).map { row ->
            row.map { (code, name) -> InlineKeyboardButton(name, "alliance:$code") }
        },
    )

    private fun actionKeyboard(includeBattle: Boolean = true): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (includeBattle) rows += listOf(InlineKeyboardButton("⚔️ В бой", "nav:battle"))
        rows += listOf(
            InlineKeyboardButton("🪖 Профиль", "nav:profile"),
            InlineKeyboardButton("🌍 Фронт", "nav:front"),
        )
        return InlineKeyboardMarkup(rows)
    }

    private fun todayKey(): String = LocalDate.now(ZoneId.of(properties.gameTimezone)).toString()

    private fun weekKey(): String {
        val date = LocalDate.now(ZoneId.of(properties.gameTimezone))
        val fields = WeekFields.ISO
        return "%04d-W%02d".format(date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()))
    }

    private fun signed(value: Int): String = if (value >= 0) "+$value" else value.toString()

    private fun helpText() = """
        Frontline Nations — доступные команды:
        /start — регистрация и выбор альянса
        /battle — выбрать операцию и тактику
        /profile — прогресс, статистика и ресурсы
        /front — состояние недельного фронта
        /contribute 100 — передать Credits на фронт
        /help — эта справка
    """.trimIndent()

    @Scheduled(cron = "0 0 0 * * *", zone = "\${frontline.game-timezone}")
    fun resetDailyOrders() {
        jdbc.sql("UPDATE players SET combat_orders = 5, updated_at = CURRENT_TIMESTAMP").update()
    }
}

data class Player(
    val telegramId: Long,
    val firstName: String,
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
)

private data class Selection(val expectedOrders: Int, val slot: Int)
