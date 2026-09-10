package com.tggames.frontline.game

import com.tggames.frontline.battle.BattleEngine
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
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
import java.util.Locale
import java.util.UUID

@Service
class GameService(
    private val jdbc: JdbcClient,
    private val telegram: TelegramClient,
    private val battleEngine: BattleEngine,
    private val properties: FrontlineProperties,
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
            val data = callback.data.orEmpty()
            if (data.startsWith("alliance:")) {
                val code = data.substringAfter(':')
                selectAlliance(callback.from.id, callback.from.firstName, code)
                telegram.answerCallback(callback.id)
                telegram.sendMessage(callback.message?.chat?.id ?: callback.from.id, profileText(callback.from.id))
            }
            return
        }

        val message = update.message ?: return
        val user = message.from ?: return
        val command = message.text.orEmpty().trim().substringBefore('@').substringBefore(' ').lowercase()
        val argument = message.text.orEmpty().trim().substringAfter(' ', "").trim()

        when (command) {
            "/start" -> start(user.id, user.firstName, message.chat.id)
            "/battle" -> battle(user.id, user.firstName, message.chat.id)
            "/profile" -> telegram.sendMessage(message.chat.id, profileText(user.id, user.firstName))
            "/front" -> telegram.sendMessage(message.chat.id, frontText())
            "/contribute" -> contribute(user.id, user.firstName, message.chat.id, argument)
            "/help", "" -> telegram.sendMessage(message.chat.id, helpText())
            else -> telegram.sendMessage(message.chat.id, "Неизвестная команда.\n\n${helpText()}")
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

    private fun battle(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, "Сначала выберите альянс:", allianceKeyboard())
            return
        }
        if (player.combatOrders <= 0) {
            telegram.sendMessage(chatId, "Боевые приказы закончились. Следующее пополнение — в 00:00 по игровому времени.")
            return
        }

        val battleId = UUID.randomUUID()
        val battle = battleEngine.resolve(
            properties.battleServerSalt,
            "$telegramId:$battleId:${player.combatOrders}",
            player.commanderLevel,
        )

        val updated = jdbc.sql(
            """
            UPDATE players
               SET combat_orders = combat_orders - 1,
                   xp = xp + :xp,
                   credits = credits + :credits,
                   materials = materials + :materials,
                   updated_at = CURRENT_TIMESTAMP
             WHERE telegram_id = :id AND combat_orders > 0
            """.trimIndent(),
        ).param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("materials", battle.materials)
            .param("id", telegramId)
            .update()

        if (updated == 0) {
            telegram.sendMessage(chatId, "Боевой приказ уже использован другим запросом. Проверьте /profile.")
            return
        }

        jdbc.sql(
            """
            INSERT INTO battles(id, player_telegram_id, victory, player_power, enemy_power, xp_reward, credits_reward, materials_reward, seed_hash, engine_version)
            VALUES (:id, :playerId, :victory, :playerPower, :enemyPower, :xp, :credits, :materials, :seedHash, 1)
            """.trimIndent(),
        ).param("id", battleId)
            .param("playerId", telegramId)
            .param("victory", battle.victory)
            .param("playerPower", battle.playerPower)
            .param("enemyPower", battle.enemyPower)
            .param("xp", battle.xp)
            .param("credits", battle.credits)
            .param("materials", battle.materials)
            .param("seedHash", battle.seedHash)
            .update()

        recordWalletChange(telegramId, "XP", battle.xp.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "CREDITS", battle.credits.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "MATERIALS", battle.materials.toLong(), "BATTLE_REWARD", battleId)

        val result = if (battle.victory) "ПОБЕДА" else "ОТСТУПЛЕНИЕ"
        telegram.sendMessage(
            chatId,
            "⚔ ОПЕРАЦИЯ ЗАВЕРШЕНА\n\n$result\nСила группы: ${battle.playerPower}\nСила противника: ${battle.enemyPower}\n\n+${battle.xp} XP\n+${battle.credits} Credits\n+${battle.materials} Materials\n\nОсталось приказов: ${player.combatOrders - 1}",
        )
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
        telegram.sendMessage(chatId, "Вклад принят: $amount Credits → $amount силы фронта.\n\n${frontText()}")
    }

    private fun profileText(telegramId: Long, firstName: String? = null): String {
        if (firstName != null) ensurePlayer(telegramId, firstName)
        val p = player(telegramId)
        val alliance = p.allianceCode?.let { alliances[it] } ?: "не выбран"
        return """
            🪖 КОМАНДИР ${p.firstName}

            Альянс: $alliance
            Уровень: ${p.commanderLevel}
            XP: ${p.xp}
            Credits: ${p.credits}
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
        "SELECT telegram_id, first_name, alliance_code, commander_level, xp, credits, materials, combat_orders FROM players WHERE telegram_id = :id",
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

    private fun weekKey(): String {
        val date = LocalDate.now(ZoneId.of(properties.gameTimezone))
        val fields = WeekFields.ISO
        return "%04d-W%02d".format(date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()))
    }

    private fun helpText() = """
        Frontline Nations — доступные команды:
        /start — регистрация и выбор альянса
        /battle — провести операцию
        /profile — профиль и ресурсы
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
    val materials: Long,
    val combatOrders: Int,
)
