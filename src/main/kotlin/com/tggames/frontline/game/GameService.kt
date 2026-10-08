package com.tggames.frontline.game

import com.fasterxml.jackson.databind.ObjectMapper
import com.tggames.frontline.battle.BattleMapDefinition
import com.tggames.frontline.battle.BattleResult
import com.tggames.frontline.battle.BattleSide
import com.tggames.frontline.battle.BattleEngine
import com.tggames.frontline.battle.DeploymentPlan
import com.tggames.frontline.battle.OperationOffer
import com.tggames.frontline.battle.SpatialBattleEvent
import com.tggames.frontline.battle.SpatialEndReason
import com.tggames.frontline.battle.SpatialEventType
import com.tggames.frontline.battle.Tactic
import com.tggames.frontline.campaign.ActiveEconomyBonus
import com.tggames.frontline.campaign.CampaignService
import com.tggames.frontline.campaign.FrontBridgeWeekState
import com.tggames.frontline.campaign.frontReservationBlockers
import com.tggames.frontline.catalog.EquipmentCatalog
import com.tggames.frontline.config.FrontlineProperties
import com.tggames.frontline.feedback.FeedbackSurface
import com.tggames.frontline.feedback.FeedbackCommentResult
import com.tggames.frontline.feedback.PlayerFeedbackService
import com.tggames.frontline.i18n.GameI18n
import com.tggames.frontline.i18n.GameLanguage
import com.tggames.frontline.inventory.Army
import com.tggames.frontline.inventory.ArmyRecoveryPlan
import com.tggames.frontline.inventory.ArmyRecoveryPlanner
import com.tggames.frontline.inventory.EquipmentAction
import com.tggames.frontline.inventory.EquipmentActionStatus
import com.tggames.frontline.inventory.InventoryService
import com.tggames.frontline.inventory.OwnedUnit
import com.tggames.frontline.inventory.RecoveryApplyStatus
import com.tggames.frontline.monetization.AdminSupportResult
import com.tggames.frontline.monetization.AnswerResult
import com.tggames.frontline.monetization.PaymentDelivery
import com.tggames.frontline.monetization.StarsCreditCatalog
import com.tggames.frontline.monetization.StarsMessages
import com.tggames.frontline.monetization.StarsPaymentService
import com.tggames.frontline.monetization.SupportCreation
import com.tggames.frontline.observability.GameMetrics
import com.tggames.frontline.observability.JourneyEventDetails
import com.tggames.frontline.observability.JourneyEventType
import com.tggames.frontline.observability.PlayerJourney
import com.tggames.frontline.observability.TechnicalFailure
import com.tggames.frontline.observability.TechnicalOperation
import com.tggames.frontline.observability.TechnicalOperationTelemetry
import com.tggames.frontline.observability.TechnicalStage
import com.tggames.frontline.progression.ForceTier
import com.tggames.frontline.progression.ForceTierCatalog
import com.tggames.frontline.progression.CommanderProgression
import com.tggames.frontline.replay.ReplayDeliveryService
import com.tggames.frontline.telegram.InlineKeyboardButton
import com.tggames.frontline.telegram.InlineKeyboardMarkup
import com.tggames.frontline.telegram.TelegramCallbackQuery
import com.tggames.frontline.telegram.TelegramClient
import com.tggames.frontline.telegram.TelegramDeliveryException
import com.tggames.frontline.telegram.TelegramUpdate
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeoutException

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
    private val replayDeliveries: ReplayDeliveryService,
    private val rankings: RankingService,
    private val stars: StarsPaymentService,
    private val metrics: GameMetrics,
    private val journey: PlayerJourney,
    private val technicalTelemetry: TechnicalOperationTelemetry,
    private val feedback: PlayerFeedbackService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(update: TelegramUpdate) {
        if (!claimUpdate(update.updateId)) return

        journey.withinTelegramUpdate(update.updateId) {
            handleClaimedUpdate(update)
        }
    }

    private fun handleClaimedUpdate(update: TelegramUpdate) {
        update.preCheckoutQuery?.let { query ->
            val validation = stars.validate(query)
            val language = runCatching { language(query.from.id) }
                .getOrElse { GameLanguage.fromTelegram(query.from.languageCode) }
            metrics.stars(if (validation.valid) "precheckout_approved" else "precheckout_rejected", StarsCreditCatalog.parsePayload(query.invoicePayload)?.packId)
            telegram.answerPreCheckout(
                query.id,
                validation.valid,
                if (validation.valid) null else StarsMessages.unavailable(language),
            )
            return
        }

        update.callbackQuery?.let { callback ->
            ensurePlayer(callback.from.id, callback.from.firstName, callback.from.languageCode)
            journey.record(callback.from.id, JourneyEventType.USER_ACTION, JourneyEventDetails(surface = "callback"))
            metrics.callback(callback.data.orEmpty())
            val replayCallback = callback.data.orEmpty().startsWith("replay:")
            if (replayCallback) telegram.answerCallback(callback.id)
            handleCallback(callback)
            if (!replayCallback) telegram.answerCallback(callback.id)
            return
        }

        val message = update.message ?: return
        val user = message.from ?: return
        val rawText = message.text.orEmpty().trim()

        message.successfulPayment?.let { payment ->
            val provisionalCommand = parseBotCommand(rawText)?.command.orEmpty()
            val provisionalArgument = parseBotCommand(rawText)?.argument.orEmpty()
            val registrationReferral = if (provisionalCommand == "/start") GameMetrics.normalizeRegistrationReferral(provisionalArgument) else null
            val registrationSource = if (registrationReferral != null) "referral" else "telegram"
            ensurePlayer(user.id, user.firstName, user.languageCode, registrationSource, registrationReferral)
            journey.record(user.id, JourneyEventType.USER_ACTION, JourneyEventDetails(surface = "payment"))
            val paymentLanguage = language(user.id)
            when (val result = stars.deliver(user.id, payment)) {
                is PaymentDelivery.Delivered -> {
                    metrics.stars("completed", result.pack.id)
                    telegram.sendMessage(
                        message.chat.id,
                        StarsMessages.paymentComplete(paymentLanguage, result.pack.credits, result.creditsBalance),
                        actionKeyboard(user.id, paymentLanguage),
                    )
                }
                is PaymentDelivery.Duplicate -> {
                    metrics.stars("duplicate")
                    telegram.sendMessage(
                        message.chat.id,
                        StarsMessages.duplicate(paymentLanguage),
                        actionKeyboard(user.id, paymentLanguage),
                    )
                }
                is PaymentDelivery.Invalid -> {
                    metrics.stars("invalid")
                    logger.warn("Rejected Stars payment for player {}: {}", user.id, result.reason)
                    telegram.sendMessage(message.chat.id, StarsMessages.invalid(paymentLanguage))
                }
            }
            return
        }

        if (rawText.isEmpty()) return

        val parsed = parseBotCommand(rawText)
        if (message.chat.isGroupChat()) {
            // In groups/supergroups ignore everything that is not a slash command addressed to this bot.
            // This prevents the bot from answering every chat message with help/unknown text.
            // Inline buttons (callback queries) are still handled in handleCallback.
            if (parsed == null) return
            if (parsed.mention != null && !parsed.mention.equals(properties.telegram.botUsername.trimStart('@'), ignoreCase = true)) return
            if (parsed.command !in GROUP_PLAYER_COMMANDS) return
        }
        val command = parsed?.command.orEmpty()
        val argument = parsed?.argument.orEmpty()
        // In private chats keep legacy behavior: unknown text answers with help.
        val effectiveCommand = when {
            command.startsWith('/') -> command
            message.chat.isGroupChat() -> return
            rawText.isEmpty() -> ""
            else -> ""
        }
        val registrationReferral = if (effectiveCommand == "/start") GameMetrics.normalizeRegistrationReferral(argument) else null
        val registrationSource = if (registrationReferral != null) "referral" else "telegram"
        ensurePlayer(user.id, user.firstName, user.languageCode, registrationSource, registrationReferral)
        journey.record(user.id, JourneyEventType.USER_ACTION, JourneyEventDetails(surface = "message"))
        val language = language(user.id)

        if (effectiveCommand.startsWith('/')) metrics.command(effectiveCommand)

        if (stars.isAdminContext(message.chat.id, user.id) && effectiveCommand in ADMIN_PAYMENT_COMMANDS) {
            handleAdminPaymentCommand(effectiveCommand, argument, message.chat.id)
            return
        }

        if (effectiveCommand.isEmpty()) {
            when (feedback.captureComment(user.id, message.chat.id, rawText)) {
                FeedbackCommentResult.STORED -> {
                    journey.record(user.id, JourneyEventType.FEEDBACK_COMMENTED, JourneyEventDetails(surface = "feedback"))
                    return
                }
                FeedbackCommentResult.EXPIRED -> return
                FeedbackCommentResult.NONE -> Unit
            }
        }

        when (effectiveCommand) {
            "/start" -> start(user.id, user.firstName, message.chat.id)
            "/battle" -> battleMenu(user.id, user.firstName, message.chat.id)
            "/army", "/hangar" -> armyMenu(user.id, message.chat.id)
            "/shop" -> shopMenu(user.id, message.chat.id)
            "/stars", "/buycredits" -> starsMenu(user.id, message.chat.id)
            "/paysupport" -> paymentSupport(user.id, message.chat.id, argument)
            "/answer" -> answerPaymentSupport(user.id, message.chat.id, argument)
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
            "/help" -> telegram.sendMessage(message.chat.id, helpText(language(user.id)), actionKeyboard(user.id, language(user.id)))
            "" -> recoverNavigation(user.id, message.chat.id, NavigationErrorCategory.UNEXPECTED_TEXT)
            else -> recoverNavigation(user.id, message.chat.id, NavigationErrorCategory.UNKNOWN_COMMAND)
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
            data == "stars:menu" -> starsMenu(callback.from.id, chatId)
            data.startsWith("stars:buy:") -> sendStarsInvoice(callback.from.id, chatId, data.removePrefix("stars:buy:"))
            data == "nav:upgrade" -> upgradeMenu(callback.from.id, chatId)
            data == "nav:daily" -> claimDailyReward(callback.from.id, chatId)
            data == "nav:profile" -> telegram.sendMessage(chatId, profileText(callback.from.id), actionKeyboard(callback.from.id, language))
            data == "nav:front" -> front(callback.from.id, callback.from.firstName, chatId)
            data == "front:previous" -> previousFront(callback.from.id, chatId)
            data == "nav:rankings" -> rankingMenu(callback.from.id, chatId)
            data == "nav:guide" -> guide(callback.from.id, chatId, 0)
            data.startsWith("rank:alliances:") -> allianceRanking(callback.from.id, chatId, data.substringAfterLast(':').toIntOrNull() ?: 0)
            data == "rank:players" -> playerRanking(callback.from.id, chatId)
            data.startsWith("guide:") -> guide(callback.from.id, chatId, data.substringAfterLast(':').toIntOrNull() ?: 0)
            data == "front:manage" -> contribute(callback.from.id, callback.from.firstName, chatId)
            data == "front:bridge" -> openFrontBridge(callback.from.id, callback.from.firstName, chatId)
            data.startsWith("front:send:") -> frontEntries(callback.from.id, chatId, data.removePrefix("front:send:"))
            data.startsWith("front:entry:") -> frontObjectives(callback.from.id, chatId, data.removePrefix("front:entry:"))
            data.startsWith("front:objective:") -> frontTactics(callback.from.id, chatId, data.removePrefix("front:objective:"))
            data.startsWith("front:review:") -> reviewFrontContribution(callback.from.id, chatId, data.removePrefix("front:review:"))
            data.startsWith("front:commit:") -> commitFrontGroup(callback.from.id, chatId, data.removePrefix("front:commit:"))
            data.startsWith("front:withdraw:") -> withdrawFrontGroup(callback.from.id, chatId, data.substringAfterLast(':').toLongOrNull())
            data == "nav:settings" -> settings(callback.from.id, chatId)
            data == "nav:country" -> countryMenu(callback.from.id, chatId, "")
            data.startsWith("first:") -> handleFirstMissionCallback(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("op:") -> showDeployment(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("deploy:") -> showObjectives(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("objective:") -> showTactics(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("fight:") -> resolveBattle(callback.from.id, callback.from.firstName, chatId, data)
            data.startsWith("result:details:") -> showBattleResultDetails(callback.from.id, chatId, data.removePrefix("result:details:"))
            data.startsWith("result:act:") -> handlePostBattleAction(callback.from.id, callback.from.firstName, chatId, data.removePrefix("result:act:"))
            data.startsWith("feedback:open:") -> {
                val surface = FeedbackSurface.from(data.removePrefix("feedback:open:")) ?: FeedbackSurface.INLINE
                if (feedback.open(callback.from.id, chatId, surface)) {
                    journey.record(callback.from.id, JourneyEventType.FEEDBACK_OPENED, JourneyEventDetails(surface = surface.value))
                }
            }
            data.startsWith("feedback:reason:") -> {
                val reason = data.removePrefix("feedback:reason:")
                if (feedback.answerReason(callback.from.id, chatId, reason)) {
                    journey.record(callback.from.id, JourneyEventType.FEEDBACK_ANSWERED, JourneyEventDetails(surface = "feedback", reason = reason))
                }
            }
            data == "feedback:comment" -> feedback.requestComment(callback.from.id, chatId)
            data == "feedback:skip" -> {
                if (feedback.skip(callback.from.id, chatId)) {
                    journey.record(callback.from.id, JourneyEventType.FEEDBACK_SKIPPED, JourneyEventDetails(surface = "feedback"))
                }
            }
            data.startsWith("replay:b:") -> sendPersonalReplay(callback.from.id, chatId, data.removePrefix("replay:b:"))
            data.startsWith("replay:w:") -> sendWeeklyReplay(callback.from.id, chatId, data.removePrefix("replay:w:"))
            data.startsWith("shop:view:") -> shopDetails(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("shop:buy:") -> acquireUnit(callback.from.id, chatId, data.removePrefix("shop:buy:"))
            data.startsWith("unit:upgrade-batch:") -> upgradeBatch(callback.from.id, chatId, data.removePrefix("unit:upgrade-batch:"))
            data.startsWith("unit:upgrade:") -> upgradeUnit(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:toggle:") -> toggleUnit(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:add:") -> addUnitType(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:remove:") -> removeUnitType(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("army:preset:") -> activatePreset(callback.from.id, chatId, data.substringAfterLast(':'))
            data.startsWith("recovery:apply:") -> applyArmyRecovery(callback.from.id, chatId, data.removePrefix("recovery:apply:"))
            data == "army:noop" -> Unit
            else -> recoverNavigation(callback.from.id, chatId, NavigationErrorCategory.STALE_CALLBACK)
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
            if (eligibleForGuidedFirstMission(current)) {
                showGuidedFirstMission(telegramId, chatId, GameI18n.t(language, "welcome_back", current.displayName), "start")
            } else {
                telegram.sendMessage(chatId, "${GameI18n.t(language, "welcome_back", current.displayName)}\n\n${profileText(telegramId)}", actionKeyboard(telegramId, language))
                recordOnboardingCtaViewedIfEligible(telegramId, "start")
            }
            return
        }
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "welcome", firstName)}\n\n${countryChoiceText(language)}",
            recommendedCountryKeyboard(language, GameLanguage.fromTelegram(current.telegramLanguage)),
        )
        journey.record(telegramId, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "start"))
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
            recordOnboardingCtaViewedIfEligible(telegramId, "country_locked")
        } else {
            campaigns.ensureCurrentWeek()
            journey.record(
                telegramId,
                JourneyEventType.ONBOARDING_COUNTRY_SELECTED,
                JourneyEventDetails(surface = "country_picker", result = "selected", referenceId = code.uppercase()),
            )
            val current = player(telegramId)
            if (eligibleForGuidedFirstMission(current)) {
                showGuidedFirstMission(telegramId, chatId, GameI18n.t(language, "country_selected", selected), "country_selected")
            } else {
                telegram.sendMessage(chatId, "${GameI18n.t(language, "country_selected", selected)}\n\n${profileText(telegramId)}", actionKeyboard(telegramId, language))
                recordOnboardingCtaViewedIfEligible(telegramId, "country_selected")
            }
        }
    }

    private fun recordOnboardingCtaViewedIfEligible(telegramId: Long, surface: String) {
        val current = player(telegramId)
        if (current.allianceCode == null || current.victories + current.defeats > 0) return
        journey.record(
            telegramId,
            JourneyEventType.ONBOARDING_CTA_VIEWED,
            JourneyEventDetails(surface = surface, result = "battle", variant = effectiveOnboardingVariant(current).value),
        )
    }

    private fun eligibleForGuidedFirstMission(player: Player): Boolean =
        player.allianceCode != null &&
            player.victories + player.defeats == 0 &&
            effectiveOnboardingVariant(player) == OnboardingVariant.GUIDED_V1

    private fun effectiveOnboardingVariant(player: Player): OnboardingVariant {
        val assigned = OnboardingVariant.fromStored(player.onboardingVariant) ?: OnboardingVariant.LEGACY
        return if (properties.onboarding.firstMissionEnabled) assigned else OnboardingVariant.LEGACY
    }

    private fun showGuidedFirstMission(telegramId: Long, chatId: Long, prefix: String, surface: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val army = inventory.army(telegramId)
        val usedCp = groupCp(army)
        when (firstMissionReadiness(
            unitCount = army.activeGroup.units.size,
            hasReservedUnits = inventory.hasReservedUnits(army.activeGroup),
            usedCp = usedCp,
            cpLimit = army.cpLimit,
            minimumBattleCp = forceTiers.minimumBattleCp,
        )) {
            FirstMissionReadiness.EMPTY -> {
                telegram.sendMessage(chatId, "$prefix\n\n${GameI18n.t(language, "army_empty")}", armyKeyboard(army, language))
                return
            }
            FirstMissionReadiness.RESERVED -> {
                telegram.sendMessage(chatId, "$prefix\n\n${GameI18n.t(language, "weekly_units_reserved")}", armyKeyboard(army, language))
                return
            }
            FirstMissionReadiness.OUTSIDE_CP_LIMIT -> {
                telegram.sendMessage(
                    chatId,
                    "$prefix\n\n${GameI18n.t(language, "group_below_minimum", usedCp, forceTiers.minimumBattleCp)}",
                    armyKeyboard(army, language),
                )
                return
            }
            FirstMissionReadiness.READY -> Unit
        }
        val group = inventory.battleSnapshot(army)
        val offerVersion = nextOfferVersion(telegramId)
        val recommendation = FirstMissionRecommendationPolicy.recommend(
            offers = offersFor(telegramId, offerVersion),
            commanderLevel = player.commanderLevel,
            group = group,
            mapFor = battleEngine::mapFor,
        )
        val entry = recommendation.map.playerEntries.first { it.id == recommendation.entryId }
        val objective = recommendation.map.objectives.first { it.id == recommendation.objectiveId }
        saveFirstMissionRecommendation(telegramId, offerVersion, army, recommendation)
        val operation = recommendation.operation
        val text = buildString {
            appendLine(prefix)
            appendLine()
            appendLine(GameI18n.t(language, "first_mission_title"))
            appendLine(GameI18n.t(language, "starter_army_ready", army.activeGroup.name, group.usedCp, group.cpLimit))
            appendLine()
            appendLine("🗺️ ${GameI18n.battlefield(language, operation.battlefield.location)} · ${GameI18n.biome(language, operation.battlefield.biome)}")
            appendLine(GameI18n.t(language, "first_mission_goal", GameI18n.t(language, objective.nameKey)))
            appendLine(GameI18n.t(language, "first_mission_orders", GameI18n.t(language, entry.nameKey), GameI18n.t(language, objective.nameKey)))
            appendLine(GameI18n.t(language, "first_mission_tactic", GameI18n.tactic(language, recommendation.tactic), GameI18n.tacticHint(language, recommendation.tactic)))
            appendLine("${GameI18n.t(language, "intel")}: ${localizedIntel(language, operation)}")
            appendLine()
            appendLine(GameI18n.t(language, "first_mission_losses"))
            append(GameI18n.t(language, "first_mission_no_guarantee"))
        }
        val keyboard = InlineKeyboardMarkup(
            listOf(
                listOf(
                    InlineKeyboardButton(
                        GameI18n.t(language, "first_mission_start"),
                        "first:start:${recommendation.version}:$offerVersion",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        GameI18n.t(language, "first_mission_configure"),
                        "first:configure:${recommendation.version}:$offerVersion",
                    ),
                ),
            ),
        )
        telegram.sendPhoto(
            chatId,
            properties.publicBaseUrl.trimEnd('/') + "/assets/maps/personal/${recommendation.map.id}.png?v=${recommendation.map.version}",
            text,
            keyboard,
        )
        journey.record(
            telegramId,
            JourneyEventType.ONBOARDING_CTA_VIEWED,
            JourneyEventDetails(
                surface = surface,
                result = "first_mission",
                variant = OnboardingVariant.GUIDED_V1.value,
                offerVersion = offerVersion,
                offerSlot = operation.slot,
                presetNo = army.activeGroup.presetNo,
                usedCp = group.usedCp,
                entryId = recommendation.entryId,
                objectiveId = recommendation.objectiveId,
                tactic = recommendation.tactic.code,
            ),
        )
    }

    private fun nextOfferVersion(telegramId: Long): Long = jdbc.sql(
        "UPDATE players SET battle_offer_version = battle_offer_version + 1, updated_at = CURRENT_TIMESTAMP WHERE telegram_id = :id RETURNING battle_offer_version",
    ).param("id", telegramId).query(Long::class.java).single()

    private fun saveFirstMissionRecommendation(
        telegramId: Long,
        offerVersion: Long,
        army: Army,
        recommendation: FirstMissionRecommendation,
    ) {
        jdbc.sql(
            """
            INSERT INTO first_mission_recommendations(
                player_telegram_id, recommendation_version, onboarding_variant,
                offer_version, offer_slot, preset_no, group_version,
                entry_id, objective_id, tactic
            ) VALUES (
                :playerId, :recommendationVersion, 'guided_v1',
                :offerVersion, :offerSlot, :presetNo, :groupVersion,
                :entryId, :objectiveId, :tactic
            )
            ON CONFLICT (player_telegram_id) DO UPDATE
               SET recommendation_version = EXCLUDED.recommendation_version,
                   onboarding_variant = EXCLUDED.onboarding_variant,
                   offer_version = EXCLUDED.offer_version,
                   offer_slot = EXCLUDED.offer_slot,
                   preset_no = EXCLUDED.preset_no,
                   group_version = EXCLUDED.group_version,
                   entry_id = EXCLUDED.entry_id,
                   objective_id = EXCLUDED.objective_id,
                   tactic = EXCLUDED.tactic,
                   shown_at = CURRENT_TIMESTAMP,
                   accepted_battle_id = NULL,
                   accepted_at = NULL
            """.trimIndent(),
        ).param("playerId", telegramId)
            .param("recommendationVersion", recommendation.version)
            .param("offerVersion", offerVersion)
            .param("offerSlot", recommendation.operation.slot)
            .param("presetNo", army.activeGroup.presetNo)
            .param("groupVersion", army.activeGroup.version)
            .param("entryId", recommendation.entryId)
            .param("objectiveId", recommendation.objectiveId)
            .param("tactic", recommendation.tactic.code)
            .update()
    }

    private fun handleFirstMissionCallback(telegramId: Long, firstName: String, chatId: Long, data: String) {
        val callback = parseFirstMissionCallback(data) ?: return staleSelection(telegramId, chatId)
        val context = firstMissionContext(telegramId, callback.offerVersion)
            ?.takeIf { it.recommendationVersion == callback.recommendationVersion }
            ?: return staleSelection(telegramId, chatId)
        val current = player(telegramId)
        if (current.battleOfferVersion != callback.offerVersion || current.victories + current.defeats > 0) return staleSelection(telegramId, chatId)
        journey.record(
            telegramId,
            JourneyEventType.ONBOARDING_CTA_CLICKED,
            JourneyEventDetails(
                surface = "first_mission_${callback.action}",
                result = callback.action,
                variant = context.onboardingVariant,
                offerVersion = context.offerVersion,
                offerSlot = context.offerSlot,
                presetNo = context.presetNo,
                entryId = context.entryId,
                objectiveId = context.objectiveId,
                tactic = context.tactic.code,
            ),
        )
        if (callback.action == "configure") {
            showDeployment(telegramId, firstName, chatId, "op:${context.offerVersion}:${context.offerSlot}")
            return
        }
        resolveBattle(
            telegramId,
            firstName,
            chatId,
            "fight:${context.offerVersion}:${context.offerSlot}:${context.entryId}:${context.objectiveId}:${context.tactic.code}:${context.presetNo}.${context.groupVersion}",
        )
    }

    private fun battleMenu(telegramId: Long, firstName: String, chatId: Long) {
        ensurePlayer(telegramId, firstName)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.victories + player.defeats == 0) {
            journey.record(
                telegramId,
                JourneyEventType.ONBOARDING_CTA_CLICKED,
                JourneyEventDetails(surface = "battle_entry", result = "battle", variant = effectiveOnboardingVariant(player).value),
            )
        }
        if (player.allianceCode == null) {
            telegram.sendMessage(chatId, countryChoiceText(language), recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            journey.record(telegramId, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "battle"))
            return
        }
        val army = inventory.army(telegramId)
        if (army.activeGroup.units.isEmpty()) {
            telegram.sendMessage(chatId, GameI18n.t(language, "army_empty"), armyKeyboard(army, language))
            return
        }
        if (inventory.hasReservedUnits(army.activeGroup)) {
            weeklyUnitsReserved(telegramId, chatId, army, language, "battle_menu")
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
        val offerVersion = nextOfferVersion(telegramId)

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
            append(GameI18n.t(language, "operation_choose"))
        }
        val keyboard = InlineKeyboardMarkup(
            offers.mapIndexed { index, offer ->
                listOf(InlineKeyboardButton("${index + 1}. ${offer.difficulty.icon} ${GameI18n.battlefield(language, offer.battlefield.location)}", "op:$offerVersion:${offer.slot}"))
            } + listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "profile"), "nav:profile"))),
        )
        telegram.sendMessage(chatId, text, keyboard)
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_OFFER_VIEWED,
            JourneyEventDetails(surface = "battle_menu", offerVersion = offerVersion, presetNo = army.activeGroup.presetNo, usedCp = deployedCp),
        )
    }

    private fun showDeployment(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parsed = parseSelection(callbackData, "op") ?: return staleSelection(telegramId, chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != parsed.expectedOfferVersion) return staleSelection(telegramId, chatId)
        val operation = offersFor(telegramId, parsed.expectedOfferVersion).getOrNull(parsed.slot) ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(telegramId, chatId, army, language, "entry")
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
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_OFFER_SELECTED,
            JourneyEventDetails(
                surface = "operation",
                offerVersion = parsed.expectedOfferVersion,
                offerSlot = parsed.slot,
                presetNo = army.activeGroup.presetNo,
                usedCp = group.usedCp,
            ),
        )
    }

    private fun showObjectives(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 5 || parts[0] != "deploy") return staleSelection(telegramId, chatId)
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return staleSelection(telegramId, chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(telegramId, chatId)
        val entryId = parts[3]
        val expectedGroup = parseGroupBinding(parts[4]) ?: return staleSelection(telegramId, chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return staleSelection(telegramId, chatId)
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(telegramId, chatId, army, language, "objective")
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return staleSelection(telegramId, chatId)
        val map = battleEngine.mapFor(operation)
        val entry = map.playerEntries.firstOrNull { it.id == entryId } ?: return staleSelection(telegramId, chatId)

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
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_DEPLOYMENT_SELECTED,
            JourneyEventDetails(
                surface = "entry",
                offerVersion = expectedOfferVersion,
                offerSlot = slot,
                presetNo = expectedGroup.presetNo,
                entryId = entryId,
            ),
        )
    }

    private fun showTactics(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val parts = callbackData.split(':')
        if (parts.size != 6 || parts[0] != "objective") return staleSelection(telegramId, chatId)
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return staleSelection(telegramId, chatId)
        val slot = parts[2].toIntOrNull() ?: return staleSelection(telegramId, chatId)
        val entryId = parts[3]
        val objectiveId = parts[4]
        val expectedGroup = parseGroupBinding(parts[5]) ?: return staleSelection(telegramId, chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return staleSelection(telegramId, chatId)
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return weeklyUnitsReserved(telegramId, chatId, army, language, "tactic")
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return staleSelection(telegramId, chatId)
        val map = battleEngine.mapFor(operation)
        val entry = map.playerEntries.firstOrNull { it.id == entryId } ?: return staleSelection(telegramId, chatId)
        val objective = map.objectives.firstOrNull { it.id == objectiveId } ?: return staleSelection(telegramId, chatId)

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
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_DEPLOYMENT_SELECTED,
            JourneyEventDetails(
                surface = "objective",
                offerVersion = expectedOfferVersion,
                offerSlot = slot,
                presetNo = expectedGroup.presetNo,
                entryId = entryId,
                objectiveId = objectiveId,
            ),
        )
    }

    private fun resolveBattle(telegramId: Long, firstName: String, chatId: Long, callbackData: String) {
        ensurePlayer(telegramId, firstName)
        val battleId = UUID.randomUUID()
        val attempt = technicalTelemetry.begin(
            TechnicalOperation.BATTLE,
            mode = "personal",
            session = journey.currentSession(telegramId),
            resourceId = battleId,
            engineVersion = PERSONAL_BATTLE_ENGINE_VERSION,
        )
        fun rejectStale() {
            technicalTelemetry.failure(attempt, TechnicalStage.ACCEPTED, TechnicalFailure.BUSINESS_STALE)
            staleSelection(telegramId, chatId)
        }
        fun rejectRule(action: () -> Unit) {
            technicalTelemetry.failure(attempt, TechnicalStage.ACCEPTED, TechnicalFailure.BUSINESS_RULE)
            action()
        }
        val parts = callbackData.split(':')
        if (parts.size != 7 || parts[0] != "fight") return rejectStale()
        val expectedOfferVersion = parts[1].toLongOrNull() ?: return rejectStale()
        val slot = parts[2].toIntOrNull() ?: return rejectStale()
        val entryId = parts[3]
        val objectiveId = parts[4]
        val tactic = Tactic.fromCode(parts[5]) ?: return rejectStale()
        val expectedGroup = parseGroupBinding(parts[6]) ?: return rejectStale()
        val player = player(telegramId, lock = true)
        val language = GameLanguage.fromStored(player.language)
        if (player.allianceCode == null || player.battleOfferVersion != expectedOfferVersion) return rejectStale()
        val operation = offersFor(telegramId, expectedOfferVersion).getOrNull(slot) ?: return rejectStale()
        val army = inventory.army(telegramId)
        if (inventory.hasReservedUnits(army.activeGroup)) return rejectRule { weeklyUnitsReserved(telegramId, chatId, army, language, "resolve") }
        if (army.activeGroup.presetNo != expectedGroup.presetNo || army.activeGroup.version != expectedGroup.version || army.activeGroup.units.isEmpty()) return rejectStale()
        val group = inventory.battleSnapshot(army)
        if (group.usedCp < forceTiers.minimumBattleCp || group.usedCp > group.cpLimit) return rejectRule { armyMenu(telegramId, chatId) }
        val map = battleEngine.mapFor(operation)
        if (map.playerEntries.none { it.id == entryId } || map.objectives.none { it.id == objectiveId }) return rejectStale()
        val plan = DeploymentPlan(entryId, objectiveId)
        val firstMission = firstMissionContext(telegramId, expectedOfferVersion)
            ?.takeIf { player.victories + player.defeats == 0 }
        val orderSelectionSource = when {
            firstMission == null -> "manual"
            firstMission.offerSlot == slot &&
                firstMission.presetNo == expectedGroup.presetNo &&
                firstMission.groupVersion == expectedGroup.version &&
                firstMission.entryId == entryId &&
                firstMission.objectiveId == objectiveId &&
                firstMission.tactic == tactic -> "recommended"
            else -> "customized"
        }
        technicalTelemetry.success(attempt, TechnicalStage.ACCEPTED)
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_DEPLOYMENT_SELECTED,
            JourneyEventDetails(
                surface = "tactic",
                battleId = battleId,
                offerVersion = expectedOfferVersion,
                offerSlot = slot,
                presetNo = expectedGroup.presetNo,
                usedCp = group.usedCp,
                entryId = entryId,
                objectiveId = objectiveId,
                tactic = tactic.name.lowercase(),
                variant = firstMission?.onboardingVariant,
            ),
        )
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_STARTED,
            JourneyEventDetails(
                surface = "personal",
                battleId = battleId,
                offerVersion = expectedOfferVersion,
                offerSlot = slot,
                presetNo = expectedGroup.presetNo,
                usedCp = group.usedCp,
                entryId = entryId,
                objectiveId = objectiveId,
                tactic = tactic.name.lowercase(),
                variant = firstMission?.onboardingVariant,
            ),
        )
        val baseBattle = try {
            battleEngine.resolve(
                properties.battleServerSalt,
                "$telegramId:${todayKey()}:$expectedOfferVersion:$slot",
                player.commanderLevel,
                operation,
                tactic,
                group,
                plan,
            )
        } catch (error: Exception) {
            technicalTelemetry.failure(attempt, TechnicalStage.RESULT_COMMITTED, technicalFailure(error, TechnicalFailure.ENGINE_FAILURE))
            throw error
        }
        val economyBonus = campaigns.activeEconomyBonus(player.allianceCode)
        val battle = applyWeeklyEconomyBonus(baseBattle, economyBonus)
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

        if (updated == 0) {
            technicalTelemetry.failure(attempt, TechnicalStage.RESULT_COMMITTED, TechnicalFailure.BUSINESS_STALE)
            return staleSelection(telegramId, chatId)
        }
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
                outcome_credits, destruction_credits, outcome_xp, destruction_xp, enemy_units_destroyed,
                order_selection_source, onboarding_variant, recommendation_version
            )
            VALUES (
                :id, :playerId, :victory, :playerPower, :enemyPower,
                :xp, :credits, 0, :materials,
                :battleSeed, :commanderLevel, :seedHash, 10, :location, :biome, :difficulty,
                :enemy, :tactic, :rounds, CAST(:events AS jsonb),
                :groupId, CAST(:groupSnapshot AS jsonb), :groupVersion, :compositionPower, 0, 0,
                :mapId, :mapVersion, :deploymentEntry, :primaryObjective,
                :enemyEntry, :enemyObjective, :enemyTactic, :endReason,
                CAST(:mapSnapshot AS jsonb), CAST(:enemyGroupSnapshot AS jsonb),
                CAST(:spatialEvents AS jsonb), CAST(:objectiveState AS jsonb),
                :forceTier, :playerDeployedCp, :enemyDeployedCp,
                :playerUnitsSurvived, :playerUnitsLost,
                :outcomeCredits, :destructionCredits, :outcomeXp, :destructionXp, :enemyUnitsDestroyed,
                :orderSelectionSource, :onboardingVariant, :recommendationVersion
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
            .param("orderSelectionSource", orderSelectionSource)
            .param("onboardingVariant", firstMission?.onboardingVariant)
            .param("recommendationVersion", firstMission?.recommendationVersion)
            .update()

        if (firstMission != null) {
            jdbc.sql(
                """
                UPDATE first_mission_recommendations
                   SET accepted_battle_id = :battleId,
                       accepted_at = CURRENT_TIMESTAMP
                 WHERE player_telegram_id = :playerId
                   AND offer_version = :offerVersion
                   AND accepted_battle_id IS NULL
                """.trimIndent(),
            ).param("battleId", battleId)
                .param("playerId", telegramId)
                .param("offerVersion", expectedOfferVersion)
                .update()
        }

        recordWalletChange(telegramId, "XP", battle.xp.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "CREDITS", battle.credits.toLong(), "BATTLE_REWARD", battleId)
        recordWalletChange(telegramId, "MATERIALS", battle.materials.toLong(), "BATTLE_REWARD", battleId)
        metrics.battle(battle.victory)
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_FINISHED,
            JourneyEventDetails(
                surface = "personal",
                result = if (battle.victory) "victory" else "defeat",
                reason = spatial.endReason.name.lowercase(),
                battleId = battleId,
                usedCp = group.usedCp,
                entryId = entryId,
                objectiveId = objectiveId,
                tactic = tactic.name.lowercase(),
                quantity = casualties.lost,
            ),
        )

        val fresh = player(telegramId)
        val freshArmy = inventory.army(telegramId)
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
        val detailsText = buildString {
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
            appendLine("+${battle.materials} ${GameI18n.t(language, "materials_label")}")
            appendLine(GameI18n.t(language, "equipment_returned_lost", casualties.survived, casualties.lost))
            appendLine("${GameI18n.t(language, "streak")}: ${fresh.currentStreak}$levelUp")
            if (fresh.victories + fresh.defeats == 1) {
                appendLine()
                append(GameI18n.t(language, "new_player_economy_hint"))
            }
        }
        val personalOutcome = if (battle.victory) PersonalBattleOutcome.VICTORY else PersonalBattleOutcome.DEFEAT
        val orderedObjectiveHeld = spatial.objectives.firstOrNull { it.id == objectiveId }?.owner == BattleSide.PLAYER
        val insight = BattleResultPresentationPolicy.insight(
            personalOutcome,
            spatial.endReason,
            objectiveId,
            spatial.objectives,
            spatial.events,
        )
        val dailyAvailable = GameUiPolicy.dailyRewardAvailable(
            fresh.dailyRewardLastClaim,
            LocalDate.now(ZoneId.of(properties.gameTimezone)),
        )
        val recommendation = BattleResultPresentationPolicy.recommendNextAction(
            unitCount = freshArmy.activeGroup.units.size,
            hasReservedUnits = inventory.hasReservedUnits(freshArmy.activeGroup),
            usedCp = groupCp(freshArmy),
            cpLimit = freshArmy.cpLimit,
            minimumBattleCp = forceTiers.minimumBattleCp,
            dailyAvailable = dailyAvailable,
        )
        val nextGoal = BattleResultPresentationPolicy.nextLevelGoal(fresh.xp)
        val summary = buildString {
            appendLine(GameI18n.t(language, when (personalOutcome) {
                PersonalBattleOutcome.VICTORY -> "result_outcome_victory"
                PersonalBattleOutcome.DEFEAT -> "result_outcome_defeat"
                PersonalBattleOutcome.DRAW -> "result_outcome_draw"
            }))
            appendLine(GameI18n.t(language, if (orderedObjectiveHeld) "result_goal_achieved" else "result_goal_not_achieved", GameI18n.t(language, objective.nameKey)))
            appendLine(GameI18n.t(language, "result_reward_summary", battle.xp, battle.credits, battle.materials))
            appendLine(GameI18n.t(language, "equipment_returned_lost", casualties.survived, casualties.lost))
            appendLine()
            appendLine(GameI18n.t(language, "result_insight", battleInsightText(language, insight)))
            append(GameI18n.t(language, "result_next_level_goal", nextGoal.level, nextGoal.xpRemaining, nextGoal.nextCapacity))
            if (recommendation.dailySuggested) {
                appendLine()
                append(GameI18n.t(language, "result_daily_available"))
            }
        }.trim()
        savePostBattleRecommendation(telegramId, battleId, recommendation, detailsText.trim())
        completeNextBattleRecommendation(telegramId, battleId)
        journey.record(
            telegramId,
            JourneyEventType.POST_BATTLE_ACTION_SELECTED,
            JourneyEventDetails(
                surface = "battle_result",
                result = recommendation.action.value,
                reason = recommendation.reason.value,
                battleId = battleId,
            ),
        )
        val firstBattleResult = fresh.victories + fresh.defeats == 1
        val resultKeyboard = postBattleKeyboard(language, battleId, recommendation.action, firstBattleResult)
        val frontBridge = prepareFrontBridgeOffer(telegramId, battleId, fresh, freshArmy, language)
        runAfterCommit {
            technicalTelemetry.success(attempt, TechnicalStage.RESULT_COMMITTED)
            try {
                telegram.sendMessage(chatId, summary, resultKeyboard)
                markPostBattleSent(telegramId, battleId)
                if (firstBattleResult) {
                    runCatching { feedback.markInlineOffer(telegramId, battleId) }
                        .onFailure { logger.warn("First-battle feedback offer could not be recorded", it) }
                }
                technicalTelemetry.success(attempt, TechnicalStage.RESULT_SENT, terminal = true)
                frontBridge?.let { offer ->
                    runCatching {
                        telegram.sendMessage(
                            chatId,
                            offer.text,
                            InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(offer.buttonText, "front:bridge")))),
                        )
                        markFrontBridgeShown(telegramId)
                        offer.recordShown()
                    }.onFailure { error ->
                        logger.warn("Front bridge after battle {} could not be delivered to player {}", battleId, telegramId, error)
                    }
                }
            } catch (error: Exception) {
                technicalTelemetry.failure(attempt, TechnicalStage.RESULT_SENT, technicalFailure(error, TechnicalFailure.TELEGRAM_SEND))
                logger.warn("Battle result {} was committed but could not be delivered to player {}", battleId, telegramId, error)
            }
        }
    }

    private fun prepareFrontBridgeOffer(
        telegramId: Long,
        battleId: UUID,
        player: Player,
        army: Army,
        language: GameLanguage,
    ): FrontBridgeOffer? {
        val allianceCode = player.allianceCode ?: return null
        val alreadyShown = jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM front_bridge_offers WHERE player_telegram_id = :player AND shown_at IS NOT NULL)",
        ).param("player", telegramId).query(Boolean::class.java).single()
        val overview = campaigns.frontBridgeOverview(telegramId, allianceCode)
        if (!FrontBridgePolicy.eligible(player.victories + player.defeats, alreadyShown, overview.alreadyContributed)) return null

        val readyGroups = FrontBridgePolicy.battleReadyGroups(frontGroupReadiness(army), forceTiers.minimumBattleCp, army.cpLimit)
        val action = FrontBridgePolicy.bridgeAction(overview.openForContributions, readyGroups)
        val persisted = jdbc.sql(
            """
            INSERT INTO front_bridge_offers(player_telegram_id, battle_id, week_key, action)
            VALUES (:player, :battle, :week, :action)
            ON CONFLICT (player_telegram_id) DO UPDATE
                SET battle_id = EXCLUDED.battle_id,
                    week_key = EXCLUDED.week_key,
                    action = EXCLUDED.action,
                    updated_at = CURRENT_TIMESTAMP
              WHERE front_bridge_offers.shown_at IS NULL
            """.trimIndent(),
        ).param("player", telegramId)
            .param("battle", battleId)
            .param("week", overview.weekKey)
            .param("action", action.value)
            .update()
        if (persisted == 0) return null

        val own = AllianceCatalog.option(allianceCode, language).label
        val opponent = overview.opponentCode?.let { AllianceCatalog.option(it, language).label }
            ?: GameI18n.t(language, "front_bridge_opponent_pending")
        val deadline = overview.resolvesAt.atZone(ZoneId.of(properties.gameTimezone))
            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        val status = when (overview.state) {
            FrontBridgeWeekState.OPEN -> GameI18n.t(language, "front_bridge_status_open", deadline)
            FrontBridgeWeekState.LOCKED -> GameI18n.t(language, "front_bridge_status_locked")
            FrontBridgeWeekState.RESOLVED -> GameI18n.t(language, "front_bridge_status_resolved")
        }
        val safety = if (action == FrontBridgeAction.CONTRIBUTE) {
            GameI18n.t(language, "front_bridge_safe", readyGroups.size - 1)
        } else {
            GameI18n.t(language, "front_bridge_unsafe")
        }
        val text = buildString {
            appendLine(GameI18n.t(language, "front_bridge_title"))
            appendLine(GameI18n.t(language, "front_bridge_intro", own, opponent))
            appendLine(status)
            appendLine(GameI18n.t(language, "front_bridge_power", formatCampaignPower(overview.ownPower), formatCampaignPower(overview.opponentPower)))
            appendLine()
            appendLine(
                GameI18n.t(
                    language,
                    "front_bridge_rewards",
                    properties.campaign.loserXp,
                    properties.campaign.loserCredits,
                    properties.campaign.loserMaterials,
                    properties.campaign.winnerXp,
                    properties.campaign.winnerCredits,
                    properties.campaign.winnerMaterials,
                ),
            )
            appendLine(GameI18n.t(language, "front_bridge_bonus", properties.campaign.victoryBonusPercent, properties.campaign.victoryBonusDays))
            appendLine(GameI18n.t(language, "front_bridge_no_guarantee"))
            appendLine()
            appendLine(GameI18n.t(language, "front_bridge_reserve_terms"))
            append(safety)
        }
        val shown = journey.deferred(
            telegramId,
            JourneyEventType.FRONT_BRIDGE_SHOWN,
            JourneyEventDetails(surface = "battle_result", result = action.value, battleId = battleId, referenceId = overview.weekKey),
        )
        return FrontBridgeOffer(
            text = text,
            buttonText = GameI18n.t(language, if (action == FrontBridgeAction.CONTRIBUTE) "front_bridge_contribute_button" else "front_bridge_view_button"),
            action = action,
            recordShown = shown,
        )
    }

    private fun markFrontBridgeShown(telegramId: Long) {
        jdbc.sql(
            "UPDATE front_bridge_offers SET shown_at = COALESCE(shown_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player",
        ).param("player", telegramId).update()
    }

    private fun savePostBattleRecommendation(
        telegramId: Long,
        battleId: UUID,
        recommendation: PostBattleRecommendation,
        detailsText: String,
    ) {
        jdbc.sql(
            """
            INSERT INTO battle_next_actions(
                battle_id, player_telegram_id, action, reason, summary_version, daily_suggested, details_text
            ) VALUES (
                :battleId, :playerId, :action, :reason, :summaryVersion, :dailySuggested, :detailsText
            )
            """.trimIndent(),
        ).param("battleId", battleId)
            .param("playerId", telegramId)
            .param("action", recommendation.action.value)
            .param("reason", recommendation.reason.value)
            .param("summaryVersion", BattleResultPresentationPolicy.SUMMARY_VERSION)
            .param("dailySuggested", recommendation.dailySuggested)
            .param("detailsText", detailsText)
            .update()
    }

    private fun markPostBattleSent(telegramId: Long, battleId: UUID) {
        runCatching {
            jdbc.sql(
                """
                UPDATE battle_next_actions
                   SET sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP)
                 WHERE battle_id = :battleId AND player_telegram_id = :playerId
                """.trimIndent(),
            ).param("battleId", battleId).param("playerId", telegramId).update()
        }.onFailure { logger.warn("Battle result {} was delivered but its delivery marker could not be stored", battleId, it) }
    }

    private fun showBattleResultDetails(telegramId: Long, chatId: Long, rawBattleId: String) {
        val language = language(telegramId)
        val battleId = runCatching { UUID.fromString(rawBattleId) }.getOrNull()
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(telegramId, language))
        val row = postBattleAction(telegramId, battleId)
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(telegramId, language))
        markBattleResultDetailsOpened(jdbc, telegramId, battleId)
        journey.record(
            telegramId,
            JourneyEventType.BATTLE_RESULT_DETAILS_OPENED,
            JourneyEventDetails(surface = "battle_result", battleId = battleId),
        )
        telegram.sendMessage(chatId, row.detailsText, postBattleKeyboard(language, battleId, row.action))
    }

    private fun handlePostBattleAction(telegramId: Long, firstName: String, chatId: Long, rawBattleId: String) {
        val language = language(telegramId)
        val battleId = runCatching { UUID.fromString(rawBattleId) }.getOrNull()
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(telegramId, language))
        val row = postBattleAction(telegramId, battleId)
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(telegramId, language))
        val army = inventory.army(telegramId)
        val current = BattleResultPresentationPolicy.recommendNextAction(
            unitCount = army.activeGroup.units.size,
            hasReservedUnits = inventory.hasReservedUnits(army.activeGroup),
            usedCp = groupCp(army),
            cpLimit = army.cpLimit,
            minimumBattleCp = forceTiers.minimumBattleCp,
            dailyAvailable = GameUiPolicy.dailyRewardAvailable(
                player(telegramId).dailyRewardLastClaim,
                LocalDate.now(ZoneId.of(properties.gameTimezone)),
            ),
        )
        val ready = current.action == PostBattleAction.NEXT_BATTLE
        val clickResult = when {
            row.action == PostBattleAction.NEXT_BATTLE && !ready -> "unavailable"
            row.action != PostBattleAction.NEXT_BATTLE && ready -> "already_ready"
            else -> "opened"
        }
        markPostBattleClicked(telegramId, row, clickResult)
        when {
            row.action == PostBattleAction.NEXT_BATTLE && ready -> battleMenu(telegramId, firstName, chatId)
            row.action == PostBattleAction.NEXT_BATTLE -> {
                telegram.sendMessage(chatId, GameI18n.t(language, "result_action_unavailable"))
                armyMenu(telegramId, chatId)
            }
            ready -> {
                completeRestorationRecommendation(telegramId, row.battleId, "already_ready")
                battleMenu(telegramId, firstName, chatId)
            }
            else -> armyMenu(telegramId, chatId)
        }
    }

    private fun markPostBattleClicked(telegramId: Long, row: PostBattleActionRow, result: String) {
        jdbc.sql(
            """
            UPDATE battle_next_actions
               SET clicked_at = COALESCE(clicked_at, CURRENT_TIMESTAMP),
                   click_count = click_count + 1,
                   last_click_result = :result
             WHERE battle_id = :battleId AND player_telegram_id = :playerId
            """.trimIndent(),
        ).param("result", result).param("battleId", row.battleId).param("playerId", telegramId).update()
        journey.record(
            telegramId,
            JourneyEventType.POST_BATTLE_ACTION_CLICKED,
            JourneyEventDetails(
                surface = "battle_result",
                result = row.action.value,
                reason = result,
                battleId = row.battleId,
            ),
        )
    }

    private fun completeNextBattleRecommendation(telegramId: Long, completionBattleId: UUID) {
        val recommendedBattleId = jdbc.sql(
            """
            SELECT battle_id
              FROM battle_next_actions
             WHERE player_telegram_id = :playerId
               AND action = 'next_battle'
               AND clicked_at IS NOT NULL
               AND completed_at IS NULL
               AND battle_id <> :completionBattleId
             ORDER BY clicked_at DESC, created_at DESC
             LIMIT 1
            """.trimIndent(),
        ).param("playerId", telegramId).param("completionBattleId", completionBattleId)
            .query(UUID::class.java).optional().orElse(null) ?: return
        val updated = jdbc.sql(
            """
            UPDATE battle_next_actions
               SET completed_at = CURRENT_TIMESTAMP,
                   completion_battle_id = :completionBattleId
             WHERE battle_id = :battleId
               AND player_telegram_id = :playerId
               AND completed_at IS NULL
            """.trimIndent(),
        ).param("completionBattleId", completionBattleId)
            .param("battleId", recommendedBattleId)
            .param("playerId", telegramId)
            .update()
        if (updated > 0) {
            journey.record(
                telegramId,
                JourneyEventType.POST_BATTLE_ACTION_COMPLETED,
                JourneyEventDetails(surface = "next_battle", result = "completed", referenceId = recommendedBattleId.toString(), battleId = completionBattleId),
            )
        }
    }

    private fun completeRestorationRecommendationIfReady(telegramId: Long) {
        val army = inventory.army(telegramId)
        val ready = army.activeGroup.units.isNotEmpty() &&
            !inventory.hasReservedUnits(army.activeGroup) &&
            groupCp(army) in forceTiers.minimumBattleCp..army.cpLimit
        if (!ready) return
        val battleId = jdbc.sql(
            """
            SELECT battle_id
              FROM battle_next_actions
             WHERE player_telegram_id = :playerId
               AND action IN ('restore_group', 'choose_group')
               AND clicked_at IS NOT NULL
               AND completed_at IS NULL
             ORDER BY clicked_at DESC, created_at DESC
             LIMIT 1
            """.trimIndent(),
        ).param("playerId", telegramId).query(UUID::class.java).optional().orElse(null) ?: return
        completeRestorationRecommendation(telegramId, battleId, "group_ready")
    }

    private fun completeRestorationRecommendation(telegramId: Long, battleId: UUID, reason: String) {
        val updated = jdbc.sql(
            """
            UPDATE battle_next_actions
               SET completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
             WHERE battle_id = :battleId
               AND player_telegram_id = :playerId
               AND completed_at IS NULL
            """.trimIndent(),
        ).param("battleId", battleId).param("playerId", telegramId).update()
        if (updated > 0) {
            journey.record(
                telegramId,
                JourneyEventType.POST_BATTLE_ACTION_COMPLETED,
                JourneyEventDetails(surface = "restore_group", result = "completed", reason = reason, battleId = battleId),
            )
        }
    }

    private fun postBattleAction(telegramId: Long, battleId: UUID): PostBattleActionRow? = jdbc.sql(
        """
        SELECT battle_id, action, details_text
          FROM battle_next_actions
         WHERE battle_id = :battleId AND player_telegram_id = :playerId
        """.trimIndent(),
    ).param("battleId", battleId).param("playerId", telegramId).query { rs, _ ->
        PostBattleActionRow(
            battleId = rs.getObject("battle_id", UUID::class.java),
            action = PostBattleAction.entries.first { it.value == rs.getString("action") },
            detailsText = rs.getString("details_text"),
        )
    }.optional().orElse(null)

    private fun sendPersonalReplay(telegramId: Long, chatId: Long, rawBattleId: String) {
        val language = language(telegramId)
        val battleId = runCatching { UUID.fromString(rawBattleId) }.getOrNull()
        if (battleId == null) return rejectReplayRequest(telegramId, chatId, language, "personal")
        replayDeliveries.requestPersonal(telegramId, chatId, language, battleId)
    }

    private fun sendWeeklyReplay(telegramId: Long, chatId: Long, rawMatchupId: String) {
        val language = language(telegramId)
        val matchupId = runCatching { UUID.fromString(rawMatchupId) }.getOrNull()
        if (matchupId == null) return rejectReplayRequest(telegramId, chatId, language, "weekly")
        replayDeliveries.requestWeekly(telegramId, chatId, language, matchupId, player(telegramId).allianceCode)
    }

    private fun rejectReplayRequest(
        telegramId: Long,
        chatId: Long,
        language: GameLanguage,
        mode: String,
    ) {
        val attempt = technicalTelemetry.begin(
            TechnicalOperation.REPLAY,
            mode = mode,
            session = journey.currentSession(telegramId),
            resourceId = null,
        )
        technicalTelemetry.success(attempt, TechnicalStage.REQUESTED)
        technicalTelemetry.failure(attempt, TechnicalStage.QUEUED, TechnicalFailure.BUSINESS_STALE)
        telegram.sendMessage(chatId, GameI18n.t(language, "replay_unavailable"), actionKeyboard(telegramId, language))
    }

    private fun staleSelection(telegramId: Long, chatId: Long) =
        recoverNavigation(telegramId, chatId, NavigationErrorCategory.STALE_CALLBACK)

    private fun recoverNavigation(telegramId: Long, chatId: Long, category: NavigationErrorCategory) {
        val current = player(telegramId)
        val language = GameLanguage.fromStored(current.language)
        val army = inventory.army(telegramId)
        val usedCp = groupCp(army)
        val battleReady = army.activeGroup.units.isNotEmpty() &&
            !inventory.hasReservedUnits(army.activeGroup) &&
            usedCp in forceTiers.minimumBattleCp..army.cpLimit
        val recovery = NavigationRecoveryPolicy.decide(
            hasCountry = current.allianceCode != null,
            hasPendingNickname = current.pendingNickname != null,
            completedBattles = current.victories + current.defeats,
            battleReady = battleReady,
            dailyAvailable = GameUiPolicy.dailyRewardAvailable(
                current.dailyRewardLastClaim,
                LocalDate.now(ZoneId.of(properties.gameTimezone)),
            ),
        )
        val rows = recovery.actions.map { action ->
            listOf(InlineKeyboardButton(GameI18n.t(language, "navigation_action_${action.value}"), action.callback))
        }
        telegram.sendMessage(
            chatId,
            GameI18n.t(language, "navigation_${category.value}", GameI18n.t(language, "navigation_stage_${recovery.stage.value}")),
            InlineKeyboardMarkup(rows),
        )
        journey.record(
            telegramId,
            JourneyEventType.NAVIGATION_ERROR,
            JourneyEventDetails(
                surface = category.value,
                result = recovery.primary.value,
                reason = recovery.stage.value,
            ),
        )
    }

    private fun weeklyUnitsReserved(telegramId: Long, chatId: Long, army: Army, language: GameLanguage, surface: String) {
        journey.record(
            telegramId,
            JourneyEventType.PERSONAL_BATTLE_BLOCKED_BY_RESERVATION,
            JourneyEventDetails(
                surface = surface,
                reason = "front_reservation",
                presetNo = army.activeGroup.presetNo,
                usedCp = groupCp(army),
            ),
        )
        telegram.sendMessage(chatId, GameI18n.t(language, "weekly_units_reserved"), armyKeyboard(army, language))
    }

    private fun offersFor(telegramId: Long, offerVersion: Long): List<OperationOffer> =
        battleEngine.offers(properties.battleServerSalt, "$telegramId:${todayKey()}:$offerVersion")

    private fun firstMissionContext(telegramId: Long, offerVersion: Long): FirstMissionContext? = jdbc.sql(
        """
        SELECT recommendation_version, onboarding_variant, offer_version, offer_slot,
               preset_no, group_version, entry_id, objective_id, tactic
          FROM first_mission_recommendations
         WHERE player_telegram_id = :playerId
           AND offer_version = :offerVersion
           AND accepted_battle_id IS NULL
        """.trimIndent(),
    ).param("playerId", telegramId)
        .param("offerVersion", offerVersion)
        .query { result, _ ->
            FirstMissionContext(
                recommendationVersion = result.getInt("recommendation_version"),
                onboardingVariant = result.getString("onboarding_variant"),
                offerVersion = result.getLong("offer_version"),
                offerSlot = result.getInt("offer_slot"),
                presetNo = result.getInt("preset_no"),
                groupVersion = result.getInt("group_version"),
                entryId = result.getString("entry_id"),
                objectiveId = result.getString("objective_id"),
                tactic = requireNotNull(Tactic.fromCode(result.getString("tactic"))),
            )
        }.optional().orElse(null)

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
        val currentPlayer = player(telegramId)
        val active = army.activeGroup
        val deployedCp = groupCp(army)
        val recovery = ArmyRecoveryPlanner.plan(army, equipment.units, currentPlayer.commanderLevel, forceTiers.minimumBattleCp)
        val snapshot = active.units.takeIf { it.isNotEmpty() }?.let { inventory.battleSnapshot(army) }
        val text = buildString {
            appendLine(GameI18n.t(language, "army_title"))
            appendLine("${GameI18n.t(language, "cp_limit")}: ${groupCp(army)}/${army.cpLimit} CP")
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
            if (deployedCp < forceTiers.minimumBattleCp || inventory.hasReservedUnits(active)) {
                appendLine()
                appendLine(GameI18n.t(language, "recovery_title"))
                appendLine(recoveryStatusText(telegramId, language, army, recovery, currentPlayer))
            }
            appendLine()
            append(GameI18n.t(language, "army_hint"))
        }
        telegram.sendMessage(chatId, text, armyKeyboard(army, language, recovery, currentPlayer))
        journey.record(
            telegramId,
            JourneyEventType.ARMY_VIEWED,
            JourneyEventDetails(surface = "army", presetNo = active.presetNo, usedCp = groupCp(army)),
        )
        if (deployedCp < forceTiers.minimumBattleCp || inventory.hasReservedUnits(active)) {
            journey.record(
                telegramId,
                JourneyEventType.ARMY_RECOVERY_BLOCKED,
                JourneyEventDetails(
                    surface = "army",
                    reason = when {
                        inventory.hasReservedUnits(active) -> "reserved"
                        active.units.isEmpty() -> "empty"
                        recovery == null -> "no_valid_plan"
                        recovery.purchaseCredits > currentPlayer.credits -> "insufficient_credits"
                        else -> "below_minimum"
                    },
                    presetNo = active.presetNo,
                    usedCp = deployedCp,
                ),
            )
        }
    }

    private fun armyKeyboard(
        army: Army,
        language: GameLanguage,
        recovery: ArmyRecoveryPlan? = null,
        currentPlayer: Player? = null,
    ): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (recovery != null && currentPlayer != null && recovery.purchaseCredits <= currentPlayer.credits) {
            rows += listOf(
                InlineKeyboardButton(
                    GameI18n.t(language, if (recovery.needsPurchase) "recovery_confirm_purchase" else "recovery_confirm_owned", recovery.purchaseCredits),
                    "recovery:apply:${recovery.groupVersion}:${recovery.signature}",
                ),
            )
        } else if (recovery != null && currentPlayer != null &&
            GameUiPolicy.dailyRewardAvailable(currentPlayer.dailyRewardLastClaim, LocalDate.now(ZoneId.of(properties.gameTimezone)))) {
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "daily"), "nav:daily"))
        }
        rows += army.groups.map { group ->
            val mark = if (group.active) "✅ " else ""
            InlineKeyboardButton("$mark${group.presetNo}. ${group.name}", "army:preset:${group.presetNo}")
        }
        val availability = GameUiPolicy.equipmentSelection(army)
        equipment.units.forEach { definition ->
            val state = availability[definition.code] ?: return@forEach
            val callbacks = GameUiPolicy.equipmentActionCallbacks(state)
            rows += listOf(
                InlineKeyboardButton("➖ ${definition.emoji} ×${state.selected}", callbacks.remove),
                InlineKeyboardButton(
                    "➕ ${definition.name(language)} ${state.selected}/${state.available}",
                    callbacks.add,
                ),
            )
        }
        rows += listOf(
            InlineKeyboardButton(GameI18n.t(language, "shop"), "nav:shop"),
            InlineKeyboardButton(GameI18n.t(language, "upgrade"), "nav:upgrade"),
        )
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "battle"), "nav:battle"))
        return InlineKeyboardMarkup(rows)
    }

    private fun recoveryStatusText(
        telegramId: Long,
        language: GameLanguage,
        army: Army,
        recovery: ArmyRecoveryPlan?,
        currentPlayer: Player,
    ): String {
        val assigned = army.groups.flatMap { it.units }.mapTo(mutableSetOf()) { it.id }
        val available = army.inventory.count { it.id !in assigned && it.reservedWeekKey == null }
        val reserved = army.inventory.count { it.reservedWeekKey != null }
        val destroyed = jdbc.sql("SELECT COUNT(*) FROM player_units WHERE player_telegram_id = :player AND destroyed_at IS NOT NULL")
            .param("player", telegramId).query(Long::class.java).single()
        val inventoryLine = GameI18n.t(language, "recovery_inventory_state", army.inventory.size, assigned.size, available, reserved, destroyed)
        if (inventory.hasReservedUnits(army.activeGroup)) {
            return "$inventoryLine\n${GameI18n.t(language, "recovery_reserved_reason")}"
        }
        if (recovery == null) return "$inventoryLine\n${GameI18n.t(language, "recovery_no_valid_plan")}"
        val ownedText = recovery.ownedUnitIds.size.takeIf { it > 0 }?.let {
            GameI18n.t(language, "recovery_owned_plan", it, recovery.ownedCp)
        }
        val purchaseText = recovery.purchases.takeIf { it.isNotEmpty() }?.joinToString(" + ") { item ->
            val definition = equipment.require(item.code)
            "${item.quantity}× ${definition.emoji} ${definition.name(language)}"
        }?.let { GameI18n.t(language, "recovery_purchase_plan", it, recovery.purchaseCredits) }
        val shortage = (recovery.purchaseCredits - currentPlayer.credits).coerceAtLeast(0)
        val resourceText = when {
            shortage == 0L -> null
            GameUiPolicy.dailyRewardAvailable(currentPlayer.dailyRewardLastClaim, LocalDate.now(ZoneId.of(properties.gameTimezone))) ->
                GameI18n.t(language, "recovery_shortage_daily", shortage)
            else -> GameI18n.t(language, "recovery_shortage_wait", shortage, properties.gameTimezone)
        }
        return listOfNotNull(
            inventoryLine,
            GameI18n.t(language, "recovery_target", recovery.currentCp, recovery.finalCp, recovery.targetCp),
            ownedText,
            purchaseText,
            resourceText,
        ).joinToString("\n")
    }

    private fun applyArmyRecovery(telegramId: Long, chatId: Long, payload: String) {
        val language = language(telegramId)
        val parts = payload.split(':')
        val expectedVersion = parts.getOrNull(0)?.toIntOrNull() ?: return armyMenu(telegramId, chatId)
        val expectedSignature = parts.getOrNull(1)?.takeIf { it.matches(Regex("[0-9a-f]{12}")) }
            ?: return armyMenu(telegramId, chatId)
        journey.record(telegramId, JourneyEventType.ARMY_RECOVERY_STARTED, JourneyEventDetails(surface = "army", referenceId = expectedVersion.toString()))
        val result = inventory.applyRecoveryPlan(telegramId, expectedVersion, expectedSignature, forceTiers.minimumBattleCp)
        when (result.status) {
            RecoveryApplyStatus.APPLIED -> {
                result.plan?.purchases?.forEach { metrics.equipment("purchase", it.code, "SUCCESS", it.quantity) }
                result.plan?.purchases?.forEach {
                    journey.record(
                        telegramId,
                        JourneyEventType.SHOP_PURCHASED,
                        JourneyEventDetails(surface = "army_recovery", result = "success", unitCode = it.code, quantity = it.quantity),
                    )
                }
                journey.record(
                    telegramId,
                    JourneyEventType.ARMY_RECOVERY_COMPLETED,
                    JourneyEventDetails(surface = "army", result = "ready", usedCp = result.plan?.finalCp, quantity = result.plan?.purchases?.sumOf { it.quantity }),
                )
                completeRestorationRecommendationIfReady(telegramId)
                telegram.sendMessage(chatId, GameI18n.t(language, "recovery_complete"))
            }
            RecoveryApplyStatus.INSUFFICIENT_CREDITS -> telegram.sendMessage(chatId, GameI18n.t(language, "recovery_changed_resources"))
            RecoveryApplyStatus.RESERVED -> telegram.sendMessage(chatId, GameI18n.t(language, "recovery_reserved_reason"))
            RecoveryApplyStatus.STALE -> telegram.sendMessage(chatId, GameI18n.t(language, "recovery_stale"))
            RecoveryApplyStatus.UNAVAILABLE -> telegram.sendMessage(chatId, GameI18n.t(language, "recovery_no_valid_plan"))
        }
        armyMenu(telegramId, chatId)
    }

    private fun shopMenu(telegramId: Long, chatId: Long) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val text = buildString {
            appendLine(GameI18n.t(language, "shop_title"))
            appendLine("Credits: ${player.credits}")
            appendLine()
            append(GameI18n.t(language, "shop_hint"))
        }
        val rows = GameUiPolicy.shopOrder(equipment.units, player.commanderLevel).map { definition ->
            val lock = if (player.commanderLevel < definition.unlockLevel) "🔒 L${definition.unlockLevel}" else "${definition.cpCost} CP"
            listOf(InlineKeyboardButton("${definition.emoji} ${definition.name(language)} · $lock", "shop:view:${definition.code}"))
        } + listOf(
            listOf(InlineKeyboardButton("⭐ ${StarsMessages.packButton(language, StarsCreditCatalog.packs.first()).substringBefore(" · ")}", "stars:menu")),
            listOf(
            InlineKeyboardButton(GameI18n.t(language, "upgrade"), "nav:upgrade"),
            InlineKeyboardButton(GameI18n.t(language, "army"), "nav:army"),
            ),
        )
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
        journey.record(telegramId, JourneyEventType.SHOP_OPENED, JourneyEventDetails(surface = "shop"))
    }

    private fun starsMenu(telegramId: Long, chatId: Long) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val rows = StarsCreditCatalog.packs.map { pack ->
            listOf(InlineKeyboardButton(StarsMessages.packButton(language, pack), "stars:buy:${pack.id}"))
        } + listOf(listOf(InlineKeyboardButton("↩️ ${GameI18n.t(language, "shop")}", "nav:shop")))
        telegram.sendMessage(chatId, StarsMessages.menu(language, player.credits), InlineKeyboardMarkup(rows))
    }

    private fun sendStarsInvoice(telegramId: Long, chatId: Long, packId: String) {
        val language = language(telegramId)
        val pack = StarsCreditCatalog.find(packId) ?: return starsMenu(telegramId, chatId)
        metrics.stars("invoice_requested", pack.id)
        telegram.sendInvoice(
            chatId = chatId,
            title = "${pack.credits} Credits",
            description = StarsMessages.invoiceTitle(language, pack.credits),
            payload = StarsCreditCatalog.payload(telegramId, pack.id),
            label = "${pack.credits} Credits",
            stars = pack.priceStars,
        )
    }

    private fun paymentSupport(telegramId: Long, chatId: Long, argument: String) {
        val language = language(telegramId)
        val payments = stars.refundablePayments(telegramId)
        if (argument.isBlank()) {
            if (payments.isEmpty()) {
                telegram.sendMessage(chatId, StarsMessages.noPurchases(language))
            } else {
                val list = payments.joinToString("\n") { "${it.id}: ${it.credits} Credits — ${it.priceStars} ⭐" }
                telegram.sendMessage(chatId, StarsMessages.supportList(language, list))
            }
            return
        }
        val parts = argument.split(Regex("\\s+"), limit = 2)
        val paymentId = parts.firstOrNull()?.toLongOrNull()
        val reason = parts.getOrNull(1)?.trim().orEmpty()
        if (paymentId == null || reason.isBlank()) {
            telegram.sendMessage(chatId, StarsMessages.supportFormat(language))
            return
        }
        when (val result = stars.createSupportRequest(telegramId, paymentId, reason)) {
            is SupportCreation.Created -> {
                telegram.sendMessage(chatId, StarsMessages.supportSubmitted(language, result.requestId))
                stars.adminChatId()?.let { adminChatId ->
                    try {
                        telegram.sendMessage(
                            adminChatId,
                            """
                            Запрос возврата #${result.requestId}
                            Игрок: $telegramId
                            Платёж: ${result.payment.id} · ${result.payment.credits} Credits за ${result.payment.priceStars} Stars
                            Причина: $reason

                            /refund ${result.requestId} — вернуть Stars
                            /reject ${result.requestId} <причина> — отклонить
                            /ask ${result.requestId} <вопрос> — уточнить
                            """.trimIndent(),
                        )
                    } catch (error: Exception) {
                        logger.error("Failed to notify payment support admin for request {}", result.requestId, error)
                    }
                }
            }
            is SupportCreation.AlreadyOpen -> telegram.sendMessage(chatId, StarsMessages.supportAlreadyOpen(language, result.requestId))
            SupportCreation.PaymentNotFound -> telegram.sendMessage(chatId, StarsMessages.supportNotFound(language))
        }
    }

    private fun answerPaymentSupport(telegramId: Long, chatId: Long, argument: String) {
        val language = language(telegramId)
        val parts = argument.split(Regex("\\s+"), limit = 2)
        val explicitId = parts.firstOrNull()?.toLongOrNull()
        val answer = if (explicitId != null) parts.getOrNull(1).orEmpty().trim() else argument.trim()
        if (answer.isBlank()) {
            telegram.sendMessage(chatId, StarsMessages.supportFormat(language).replace("/paysupport", "/answer"))
            return
        }
        when (val result = stars.answer(telegramId, explicitId, answer)) {
            is AnswerResult.Accepted -> {
                telegram.sendMessage(chatId, StarsMessages.answerAccepted(language, result.requestId))
                stars.adminChatId()?.let { adminChatId ->
                    try {
                        telegram.sendMessage(adminChatId, "Ответ игрока $telegramId по запросу #${result.requestId}:\n$answer")
                    } catch (error: Exception) {
                        logger.error("Failed to forward payment support answer {}", result.requestId, error)
                    }
                }
            }
            AnswerResult.NotFound -> telegram.sendMessage(chatId, StarsMessages.supportNotFound(language))
        }
    }

    private fun handleAdminPaymentCommand(command: String, argument: String, chatId: Long) {
        val parts = argument.split(Regex("\\s+"), limit = 2)
        val requestId = parts.firstOrNull()?.toLongOrNull()
        if (requestId == null) {
            telegram.sendMessage(chatId, "Нужен ID запроса.")
            return
        }
        when (command) {
            "/refund" -> when (val result = stars.refund(requestId)) {
                is AdminSupportResult.Updated -> {
                    metrics.stars("refunded")
                    telegram.sendMessage(chatId, "Запрос #$requestId: Stars возвращены, Credits списаны.")
                    trySendPaymentSupportMessage(result.playerTelegramId, StarsMessages.refunded(languageByChat(result.playerTelegramId), requestId))
                }
                AdminSupportResult.NotFound -> telegram.sendMessage(chatId, "Запрос #$requestId не найден.")
                AdminSupportResult.AlreadyResolved -> telegram.sendMessage(chatId, "Запрос #$requestId уже закрыт.")
            }
            "/reject" -> {
                val reason = parts.getOrNull(1)?.trim().orEmpty()
                if (reason.isBlank()) return telegram.sendMessage(chatId, "Используйте /reject <ID> <причина>.")
                when (val result = stars.reject(requestId, reason)) {
                    is AdminSupportResult.Updated -> {
                        telegram.sendMessage(chatId, "Запрос #$requestId отклонён.")
                        trySendPaymentSupportMessage(result.playerTelegramId, StarsMessages.rejected(languageByChat(result.playerTelegramId), requestId, reason))
                    }
                    AdminSupportResult.NotFound -> telegram.sendMessage(chatId, "Запрос #$requestId не найден.")
                    AdminSupportResult.AlreadyResolved -> telegram.sendMessage(chatId, "Запрос #$requestId уже закрыт.")
                }
            }
            "/ask" -> {
                val question = parts.getOrNull(1)?.trim().orEmpty()
                if (question.isBlank()) return telegram.sendMessage(chatId, "Используйте /ask <ID> <вопрос>.")
                when (val result = stars.ask(requestId, question)) {
                    is AdminSupportResult.Updated -> {
                        telegram.sendMessage(chatId, "Уточнение по запросу #$requestId отправлено.")
                        trySendPaymentSupportMessage(result.playerTelegramId, StarsMessages.informationRequested(languageByChat(result.playerTelegramId), requestId, question))
                    }
                    AdminSupportResult.NotFound -> telegram.sendMessage(chatId, "Запрос #$requestId не найден.")
                    AdminSupportResult.AlreadyResolved -> telegram.sendMessage(chatId, "Запрос #$requestId уже закрыт.")
                }
            }
        }
    }

    private fun trySendPaymentSupportMessage(chatId: Long, text: String) {
        try {
            telegram.sendMessage(chatId, text)
        } catch (error: Exception) {
            logger.warn("Payment support result could not be delivered to player {}", chatId, error)
        }
    }

    private fun shopDetails(telegramId: Long, chatId: Long, code: String) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val definition = equipment.get(code) ?: return shopMenu(telegramId, chatId)
        val stats = definition.stats
        val state = if (player.commanderLevel >= definition.unlockLevel) "✅" else "🔒 ${GameI18n.t(language, "level")} ${definition.unlockLevel}"
        val text = """
            ${definition.emoji} ${definition.name(language)} · $state
            Credits: ${player.credits}

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
            ${GameI18n.t(language, "upgrade_growth")}
        """.trimIndent()
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (player.commanderLevel >= definition.unlockLevel) {
            rows += listOf(
                InlineKeyboardButton("💳 ${GameI18n.t(language, "buy")} ×1", "shop:buy:${definition.code}:1"),
                InlineKeyboardButton("💳 ×5", "shop:buy:${definition.code}:5"),
                InlineKeyboardButton("💳 ×25", "shop:buy:${definition.code}:25"),
            )
        }
        rows += listOf(InlineKeyboardButton("↩️ ${GameI18n.t(language, "shop")}", "nav:shop"))
        val keyboard = InlineKeyboardMarkup(rows)
        telegram.sendPhoto(chatId, properties.publicBaseUrl.trimEnd('/') + definition.iconPath, text, keyboard)
    }

    private fun acquireUnit(telegramId: Long, chatId: Long, payload: String) {
        val language = language(telegramId)
        val parts = payload.split(':')
        val code = parts.firstOrNull().orEmpty()
        val quantity = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val action = inventory.acquire(telegramId, code, quantity)
        metrics.equipment("purchase", action.definition?.code ?: code, action.status.name, quantity)
        if (action.status == EquipmentActionStatus.SUCCESS) {
            journey.record(
                telegramId,
                JourneyEventType.SHOP_PURCHASED,
                JourneyEventDetails(
                    surface = "shop",
                    result = "success",
                    unitCode = action.definition?.code ?: code,
                    quantity = action.quantity,
                ),
            )
        }
        val message = when (action.status) {
            EquipmentActionStatus.INSUFFICIENT_RESOURCES -> {
                val required = (action.definition?.buyCredits ?: 0) * quantity
                val current = player(telegramId)
                val missing = (required - current.credits).coerceAtLeast(0)
                val today = LocalDate.now(ZoneId.of(properties.gameTimezone))
                if (GameUiPolicy.dailyRewardAvailable(current.dailyRewardLastClaim, today)) {
                    GameI18n.t(language, "purchase_shortage_daily", missing, required, current.credits)
                } else {
                    GameI18n.t(language, "purchase_shortage_wait", missing, required, current.credits, properties.gameTimezone)
                }
            }
            EquipmentActionStatus.LOCKED -> GameI18n.t(language, "purchase_locked_next", action.definition?.unlockLevel ?: 1)
            else -> actionMessage(action, language, "insufficient_credits")
        }
        telegram.sendMessage(chatId, message)
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
            "${GameI18n.t(language, "upgrade_title")}\nCredits: ${player.credits} · ${GameI18n.t(language, "materials_label")}: ${player.materials}\n\n${GameI18n.t(language, "upgrade_growth")}\n\n$details",
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
        metrics.equipment("upgrade", action.definition?.code ?: parts[0], action.status.name, action.quantity)
        if (action.status == EquipmentActionStatus.SUCCESS) {
            journey.record(
                telegramId,
                JourneyEventType.UPGRADE_COMPLETED,
                JourneyEventDetails(surface = "upgrade", result = "success", unitCode = action.definition?.code ?: parts[0], quantity = action.quantity),
            )
        }
        telegram.sendMessage(chatId, actionMessage(action, language))
        upgradeMenu(telegramId, chatId)
    }

    private fun upgradeUnit(telegramId: Long, chatId: Long, rawId: String) {
        val language = language(telegramId)
        val unitId = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return upgradeMenu(telegramId, chatId)
        val action = inventory.upgrade(telegramId, unitId)
        metrics.equipment("upgrade", action.definition?.code, action.status.name, action.quantity)
        if (action.status == EquipmentActionStatus.SUCCESS) {
            journey.record(
                telegramId,
                JourneyEventType.UPGRADE_COMPLETED,
                JourneyEventDetails(surface = "upgrade", result = "success", unitCode = action.definition?.code, quantity = action.quantity),
            )
        }
        telegram.sendMessage(chatId, actionMessage(action, language))
        upgradeMenu(telegramId, chatId)
    }

    private fun toggleUnit(telegramId: Long, chatId: Long, rawId: String) {
        val language = language(telegramId)
        val unitId = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return armyMenu(telegramId, chatId)
        val action = inventory.toggleInActiveGroup(telegramId, unitId)
        recordArmyChange(telegramId, action, "toggle")
        if (action.status == EquipmentActionStatus.SUCCESS) completeRestorationRecommendationIfReady(telegramId)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun addUnitType(telegramId: Long, chatId: Long, code: String) {
        val language = language(telegramId)
        val action = inventory.addUnitTypeToActiveGroup(telegramId, code)
        recordArmyChange(telegramId, action, "add")
        if (action.status == EquipmentActionStatus.SUCCESS) completeRestorationRecommendationIfReady(telegramId)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun removeUnitType(telegramId: Long, chatId: Long, code: String) {
        val language = language(telegramId)
        val action = inventory.removeUnitTypeFromActiveGroup(telegramId, code)
        recordArmyChange(telegramId, action, "remove")
        if (action.status == EquipmentActionStatus.SUCCESS) completeRestorationRecommendationIfReady(telegramId)
        if (action.status != EquipmentActionStatus.SUCCESS) telegram.sendMessage(chatId, actionMessage(action, language))
        armyMenu(telegramId, chatId)
    }

    private fun activatePreset(telegramId: Long, chatId: Long, rawPreset: String) {
        val presetNo = rawPreset.toIntOrNull() ?: 0
        val changed = inventory.activatePreset(telegramId, presetNo)
        if (changed) {
            journey.record(
                telegramId,
                JourneyEventType.ARMY_CHANGED,
                JourneyEventDetails(surface = "preset", result = "success", presetNo = presetNo),
            )
            completeRestorationRecommendationIfReady(telegramId)
        }
        armyMenu(telegramId, chatId)
    }

    private fun recordArmyChange(telegramId: Long, action: EquipmentAction, surface: String) {
        if (action.status != EquipmentActionStatus.SUCCESS) return
        val army = inventory.army(telegramId)
        journey.record(
            telegramId,
            JourneyEventType.ARMY_CHANGED,
            JourneyEventDetails(
                surface = surface,
                result = "success",
                presetNo = army.activeGroup.presetNo,
                usedCp = groupCp(army),
                unitCode = action.definition?.code,
                quantity = action.quantity,
            ),
        )
    }

    private fun actionMessage(
        action: EquipmentAction,
        language: GameLanguage,
        insufficientResourcesKey: String = "insufficient_resources",
    ): String {
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
            EquipmentActionStatus.ASSIGNED_TO_GROUP -> GameI18n.t(language, "equipment_assigned_to_group")
            EquipmentActionStatus.INSUFFICIENT_RESOURCES -> GameI18n.t(language, insufficientResourcesKey)
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

    private fun frontGroupReadiness(army: Army): List<FrontGroupReadiness> = army.groups.map { group ->
        FrontGroupReadiness(
            presetNo = group.presetNo,
            name = group.name,
            usedCp = group.units.sumOf { equipment.require(it.code).cpCost },
            reserved = inventory.hasReservedUnits(group),
        )
    }

    private fun formatCampaignPower(power: Long): String {
        val whole = power / 100
        val remainder = power % 100
        return if (remainder == 0L) "$whole CP" else "$whole.${remainder.toString().padStart(2, '0').trimEnd('0')} CP"
    }

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
        val economyBonusText = economyBonus?.let { "\n${GameI18n.t(language, "weekly_victory_bonus_daily_applied")}" }.orEmpty()
        telegram.sendMessage(chatId, GameI18n.t(language, "daily_claimed", reward.credits, reward.streak) + bonusUnitText + economyBonusText, actionKeyboard(telegramId, language))
        journey.record(
            telegramId,
            JourneyEventType.DAILY_CLAIMED,
            JourneyEventDetails(surface = "daily", result = "success", referenceId = today.toString(), quantity = reward.credits.toInt()),
        )
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
        val deployment = campaigns.frontDeployment(player.allianceCode)
        val text = buildString {
            appendLine(GameI18n.t(language, "front_groups_title"))
            appendLine(GameI18n.t(language, "front_groups_help"))
            appendLine()
            army.groups.forEach { group ->
                val cp = group.units.sumOf { equipment.require(it.code).cpCost }
                val composition = if (group.units.isEmpty()) {
                    GameI18n.t(language, "empty_short")
                } else {
                    group.units.groupBy { it.code to it.level }.entries.joinToString(", ") { (key, units) ->
                        "${units.size}× ${unitLabel(key.first, key.second, language)}"
                    }
                }
                appendLine("${group.presetNo}. ${group.name} · $cp/${army.cpLimit} CP")
                appendLine("   $composition")
                sent[group.presetNo]?.let {
                    val entry = deployment?.entries?.firstOrNull { entry -> entry.id == it.entryId }
                    val objective = deployment?.objectives?.firstOrNull { objective -> objective.id == it.primaryObjectiveId }
                    val entryText = entry?.let { value -> "${deployment.entryMarker(value.id)} · ${GameI18n.t(language, value.nameKey)}" } ?: (it.entryId ?: "—")
                    val objectiveText = objective?.let { value -> "${deployment.objectiveMarker(value.id)} · ${GameI18n.t(language, value.nameKey)}" }
                        ?: GameI18n.t(language, "automatic_target")
                    appendLine("   ✅ $entryText → $objectiveText · ${GameI18n.tactic(language, it.tactic)}")
                }
                val blockers = frontReservationBlockers(group.presetNo, group.units.map { it.id }, sent.values)
                if (blockers.isNotEmpty()) {
                    appendLine("   ⚠️ ${GameI18n.t(language, "front_units_reserved_by_groups", blockers.joinToString(", "))}")
                }
            }
            appendLine()
            append(GameI18n.t(language, "front_reservation_notice"))
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

    private fun openFrontBridge(telegramId: Long, firstName: String, chatId: Long) {
        val offer = jdbc.sql(
            "SELECT action FROM front_bridge_offers WHERE player_telegram_id = :player AND shown_at IS NOT NULL",
        ).param("player", telegramId).query(String::class.java).optional().orElse(null)
            ?: return front(telegramId, firstName, chatId)
        val firstClick = jdbc.sql(
            "UPDATE front_bridge_offers SET clicked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE player_telegram_id = :player AND clicked_at IS NULL",
        ).param("player", telegramId).update() > 0
        if (firstClick) {
            journey.record(
                telegramId,
                JourneyEventType.FRONT_BRIDGE_CLICKED,
                JourneyEventDetails(surface = "battle_result", result = offer),
            )
        }
        val player = player(telegramId)
        val alliance = player.allianceCode ?: return front(telegramId, firstName, chatId)
        val army = inventory.army(telegramId)
        val readyGroups = FrontBridgePolicy.battleReadyGroups(frontGroupReadiness(army), forceTiers.minimumBattleCp, army.cpLimit)
        val overview = campaigns.frontBridgeOverview(telegramId, alliance)
        if (offer == FrontBridgeAction.CONTRIBUTE.value && overview.openForContributions && readyGroups.size >= 2 && !overview.alreadyContributed) {
            contribute(telegramId, firstName, chatId)
        } else {
            front(telegramId, firstName, chatId)
        }
    }

    private fun frontEntries(telegramId: Long, chatId: Long, binding: String) {
        val expected = parseGroupBinding(binding) ?: return staleSelection(telegramId, chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(telegramId, chatId)
        if (group.units.isEmpty()) return telegram.sendMessage(chatId, GameI18n.t(language, "army_empty"), actionKeyboard(telegramId, language))
        val deployment = campaigns.frontDeployment(alliance)
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "front_no_open_battle"), actionKeyboard(telegramId, language))
        val text = buildString {
            appendLine("🚩 ${group.name}")
            appendLine(GameI18n.t(language, "front_choose_entry"))
        }
        val buttons = deployment.entries.map { entry ->
            listOf(InlineKeyboardButton("${deployment.entryMarker(entry.id)} · ${GameI18n.t(language, entry.nameKey)}", "front:entry:$binding:${entry.id}"))
        }
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun frontObjectives(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 2) return staleSelection(telegramId, chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(telegramId, chatId)
        val entryId = parts[1]
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(telegramId, chatId)
        val deployment = p.allianceCode?.let { campaigns.frontDeployment(it) } ?: return staleSelection(telegramId, chatId)
        val entry = deployment.entries.firstOrNull { it.id == entryId } ?: return staleSelection(telegramId, chatId)
        val text = buildString {
            appendLine("🚩 ${group.name} → ${deployment.entryMarker(entry.id)} · ${GameI18n.t(language, entry.nameKey)}")
            appendLine(GameI18n.t(language, "front_choose_objective"))
        }
        val buttons = deployment.objectives.map { objective ->
            listOf(InlineKeyboardButton("${deployment.objectiveMarker(objective.id)} · ${GameI18n.t(language, objective.nameKey)}", "front:objective:${parts[0]}:$entryId:${objective.id}"))
        }
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun frontTactics(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 3) return staleSelection(telegramId, chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(telegramId, chatId)
        val entryId = parts[1]
        val objectiveId = parts[2]
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(telegramId, chatId)
        val deployment = p.allianceCode?.let { campaigns.frontDeployment(it) } ?: return staleSelection(telegramId, chatId)
        val entry = deployment.entries.firstOrNull { it.id == entryId } ?: return staleSelection(telegramId, chatId)
        val objective = deployment.objectives.firstOrNull { it.id == objectiveId } ?: return staleSelection(telegramId, chatId)
        val text = buildString {
            appendLine("🚩 ${group.name} → ${deployment.entryMarker(entry.id)} · ${GameI18n.t(language, entry.nameKey)} → ${deployment.objectiveMarker(objective.id)} · ${GameI18n.t(language, objective.nameKey)}")
            appendLine(GameI18n.t(language, "choose_tactic_spatial"))
            Tactic.entries.forEach { appendLine("${it.icon} ${GameI18n.tactic(language, it)} — ${GameI18n.tacticHint(language, it)}") }
        }
        val buttons = Tactic.entries.map { tactic ->
            InlineKeyboardButton("${tactic.icon} ${GameI18n.tactic(language, tactic)}", "front:review:${parts[0]}:$entryId:$objectiveId:${tactic.code}")
        }.chunked(2)
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(buttons))
    }

    private fun reviewFrontContribution(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 4) return staleSelection(telegramId, chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(telegramId, chatId)
        val tactic = Tactic.fromCode(parts[3]) ?: return staleSelection(telegramId, chatId)
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val alliance = player.allianceCode ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(telegramId, chatId)
        if (group.units.isEmpty()) return telegram.sendMessage(chatId, GameI18n.t(language, "army_empty"), actionKeyboard(telegramId, language))
        val deployment = campaigns.frontDeployment(alliance) ?: return front(telegramId, player.firstName, chatId)
        val entry = deployment.entries.firstOrNull { it.id == parts[1] } ?: return staleSelection(telegramId, chatId)
        val objective = deployment.objectives.firstOrNull { it.id == parts[2] } ?: return staleSelection(telegramId, chatId)
        val overview = campaigns.frontBridgeOverview(telegramId, alliance)
        if (!overview.openForContributions) return front(telegramId, player.firstName, chatId)

        val cp = group.units.sumOf { equipment.require(it.code).cpCost }
        val composition = group.units.groupBy { it.code to it.level }.entries.joinToString(", ") { (key, units) ->
            "${units.size}× ${unitLabel(key.first, key.second, language)}"
        }
        val otherReady = FrontBridgePolicy.battleReadyGroups(
            frontGroupReadiness(army),
            forceTiers.minimumBattleCp,
            army.cpLimit,
            excludingPreset = group.presetNo,
        )
        val safe = otherReady.isNotEmpty()
        val deadline = overview.resolvesAt.atZone(ZoneId.of(properties.gameTimezone))
            .format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        val text = buildString {
            appendLine(GameI18n.t(language, "front_review_title"))
            appendLine("${group.name} · $cp/${army.cpLimit} CP")
            appendLine(composition)
            appendLine("${deployment.entryMarker(entry.id)} · ${GameI18n.t(language, entry.nameKey)} → ${deployment.objectiveMarker(objective.id)} · ${GameI18n.t(language, objective.nameKey)}")
            appendLine("${tactic.icon} ${GameI18n.tactic(language, tactic)}")
            appendLine()
            appendLine(GameI18n.t(language, "front_review_deadline", deadline))
            appendLine(GameI18n.t(language, "front_reservation_notice"))
            appendLine(GameI18n.t(language, "front_review_withdrawal"))
            append(
                if (safe) GameI18n.t(language, "front_review_group_remains", otherReady.joinToString(", ") { it.name })
                else GameI18n.t(language, "front_review_no_group_remains"),
            )
        }
        val confirm = "front:commit:${parts[0]}:${parts[1]}:${parts[2]}:${tactic.code}"
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (safe) {
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front_review_confirm"), confirm))
        } else {
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front_review_prepare_group"), "nav:army"))
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front_review_confirm_anyway"), confirm))
        }
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front_review_back"), "front:manage"))
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(rows))
    }

    private fun commitFrontGroup(telegramId: Long, chatId: Long, payload: String) {
        val parts = payload.split(':')
        if (parts.size != 4) return staleSelection(telegramId, chatId)
        val expected = parseGroupBinding(parts[0]) ?: return staleSelection(telegramId, chatId)
        val tactic = Tactic.fromCode(parts[3]) ?: return staleSelection(telegramId, chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val alliance = p.allianceCode ?: return staleSelection(telegramId, chatId)
        val army = inventory.army(telegramId)
        val group = army.groups.firstOrNull { it.presetNo == expected.presetNo && it.version == expected.version }
            ?: return staleSelection(telegramId, chatId)
        val snapshot = inventory.battleSnapshot(army, group)
        val outcome = campaigns.contribute(telegramId, alliance, group.presetNo, snapshot, group.units.map { it.id }, parts[1], parts[2], tactic, language)
        if (outcome.accepted) {
            journey.record(
                telegramId,
                JourneyEventType.CONTRIBUTION_COMMITTED,
                JourneyEventDetails(
                    surface = "front",
                    result = "accepted",
                    presetNo = group.presetNo,
                    usedCp = snapshot.usedCp,
                    entryId = parts[1],
                    objectiveId = parts[2],
                    tactic = tactic.name.lowercase(),
                ),
            )
        }
        telegram.sendMessage(chatId, outcome.message)
        contribute(telegramId, p.firstName, chatId)
    }

    private fun withdrawFrontGroup(telegramId: Long, chatId: Long, contributionId: Long?) {
        if (contributionId == null) return staleSelection(telegramId, chatId)
        val p = player(telegramId)
        val language = GameLanguage.fromStored(p.language)
        val outcome = campaigns.withdraw(telegramId, contributionId, language)
        if (outcome.accepted) {
            journey.record(
                telegramId,
                JourneyEventType.CONTRIBUTION_WITHDRAWN,
                JourneyEventDetails(surface = "front", result = "accepted", referenceId = contributionId.toString()),
            )
        }
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
            GameI18n.t(language, "weekly_victory_bonus_profile", endDate)
        }
        val profile = """
            🪖 ${GameI18n.t(language, "commander")} ${p.displayName}

            ${GameI18n.t(language, "alliance")}: $alliance
            ${GameI18n.t(language, "level")}: ${p.commanderLevel} · $levelProgress
            ${GameI18n.t(language, "victories")}: ${p.victories}/$battles · $winRate%
            ${GameI18n.t(language, "streak")}: ${p.currentStreak} · ${GameI18n.t(language, "record")} ${p.bestStreak}

            Credits: ${p.credits}
            ${GameI18n.t(language, "materials_label")}: ${p.materials}
            ${GameI18n.t(language, "command_capacity")}: ${p.commandCapacity} CP
            ${GameI18n.t(language, "daily_reward_streak")}: ${p.dailyRewardStreak}/100
        """
        val onboardingHint = GameI18n.t(language, "new_player_economy_hint").takeIf { battles <= 1 }
        return formatProfileSections(profile, economyBonusText, onboardingHint)
    }

    private fun rankingMenu(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val text = GameI18n.t(language, "ratings_menu")
        val keyboard = InlineKeyboardMarkup(
            listOf(
                listOf(InlineKeyboardButton(GameI18n.t(language, "alliances_button"), "rank:alliances:0")),
                listOf(InlineKeyboardButton(GameI18n.t(language, "players_button"), "rank:players")),
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
            appendLine(GameI18n.t(language, "alliance_rating_title"))
            appendLine()
            ranking.entries.forEach { row ->
                val marker = if (row.code == p.allianceCode) " ←" else ""
                appendLine("${row.place}. ${AllianceCatalog.option(row.code, language).label} — ${row.rating} · ${row.wins}/${row.games}$marker")
            }
            ranking.ownPlace?.let { appendLine("\n${GameI18n.t(language, "your_alliance")}: #$it") }
            append("${ranking.page + 1}/${ranking.pages}")
        }
        val nav = mutableListOf<InlineKeyboardButton>()
        if (ranking.page > 0) nav += InlineKeyboardButton("⬅️", "rank:alliances:${ranking.page - 1}")
        if (ranking.page + 1 < ranking.pages) nav += InlineKeyboardButton("➡️", "rank:alliances:${ranking.page + 1}")
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (nav.isNotEmpty()) rows += nav
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "players_button"), "rank:players"))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
    }

    private fun playerRanking(telegramId: Long, chatId: Long) {
        val language = language(telegramId)
        val ranking = rankings.players(telegramId)
        val text = buildString {
            appendLine(GameI18n.t(language, "players_rating_title"))
            appendLine()
            ranking.top.forEach { row ->
                val marker = if (row.telegramId == telegramId) " ←" else ""
                appendLine("${row.place}. ${row.name.take(32)} — ${row.xp} XP · L${row.level}$marker")
            }
            if (ranking.top.none { it.telegramId == telegramId }) {
                appendLine("\n…\n${ranking.own.place}. ${ranking.own.name.take(32)} — ${ranking.own.xp} XP · L${ranking.own.level} ←")
            }
        }
        telegram.sendMessage(chatId, text.trim(), InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(GameI18n.t(language, "alliances_button"), "rank:alliances:0")))))
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
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "menu_button"), "nav:profile"))
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
        if (campaigns.hasPreviousBattle(allianceCode)) {
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "previous_battle_button"), "front:previous"))
        }
        if (allianceCode != null) rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front_groups_button"), "front:manage"))
        rows += actionKeyboard(telegramId, language).inlineKeyboard
        telegram.sendMessage(chatId, campaigns.frontText(telegramId, allianceCode, language), InlineKeyboardMarkup(rows))
        journey.record(telegramId, JourneyEventType.FRONT_VIEWED, JourneyEventDetails(surface = "front"))
    }

    private fun previousFront(telegramId: Long, chatId: Long) {
        val player = player(telegramId)
        val language = GameLanguage.fromStored(player.language)
        val text = campaigns.previousFrontText(player.allianceCode, language)
            ?: return telegram.sendMessage(chatId, GameI18n.t(language, "stale"), actionKeyboard(telegramId, language))
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        campaigns.frontReplayId(player.allianceCode)?.let {
            rows += listOf(InlineKeyboardButton(GameI18n.t(language, "replay_button"), "replay:w:$it"))
        }
        rows += listOf(InlineKeyboardButton(GameI18n.t(language, "front"), "nav:front"))
        telegram.sendMessage(chatId, text, InlineKeyboardMarkup(rows))
    }

    private fun postBattleKeyboard(
        language: GameLanguage,
        battleId: UUID,
        action: PostBattleAction,
        includeFeedback: Boolean = false,
    ): InlineKeyboardMarkup = InlineKeyboardMarkup(
        buildList {
            add(
                listOf(
                    InlineKeyboardButton(
                        GameI18n.t(language, when (action) {
                            PostBattleAction.NEXT_BATTLE -> "result_primary_next_battle"
                            PostBattleAction.RESTORE_GROUP -> "result_primary_restore_group"
                            PostBattleAction.CHOOSE_GROUP -> "result_primary_choose_group"
                        }),
                        "result:act:$battleId",
                    ),
                ),
            )
            add(
                listOf(
                    InlineKeyboardButton(GameI18n.t(language, "result_details_button"), "result:details:$battleId"),
                    InlineKeyboardButton(GameI18n.t(language, "replay_button"), "replay:b:$battleId"),
                ),
            )
            if (includeFeedback) add(listOf(feedback.inlineButton(language)))
        },
    )

    private fun battleInsightText(language: GameLanguage, insight: BattleInsight): String = GameI18n.t(
        language,
        when (insight) {
            BattleInsight.ORDERED_OBJECTIVE_HELD -> "result_insight_objective_held"
            BattleInsight.ORDERED_OBJECTIVE_ENEMY_HELD -> "result_insight_enemy_held"
            BattleInsight.ORDERED_OBJECTIVE_PROGRESS -> "result_insight_progress"
            BattleInsight.ENEMY_DEFEATED_BEFORE_OBJECTIVE -> "result_insight_enemy_defeated"
            BattleInsight.PLAYER_DESTROYED_BEFORE_OBJECTIVE -> "result_insight_player_destroyed"
            BattleInsight.PLAYER_WITHDREW_BEHIND -> "result_insight_player_withdrew"
            BattleInsight.OBJECTIVE_NOT_REACHED -> "result_insight_not_reached"
        },
    )

    private fun ensurePlayer(
        telegramId: Long,
        firstName: String,
        telegramLanguage: String? = null,
        registrationSource: String = "telegram",
        registrationReferral: String? = null,
    ) {
        val inferred = GameLanguage.fromTelegram(telegramLanguage).code
        val source = GameMetrics.normalizeRegistrationSource(registrationSource)
        val referral = if (source == "referral") GameMetrics.normalizeRegistrationReferral(registrationReferral) ?: "other" else null
        val onboardingVariant = FirstMissionRecommendationPolicy.assignedVariant(
            telegramId,
            properties.onboarding.firstMissionRolloutPercent,
        ).value
        val created = jdbc.sql(
            """
            INSERT INTO players(
                telegram_id, first_name, language, telegram_language,
                registration_source, registration_referral, onboarding_variant
            )
            VALUES (
                :id, :firstName, :language, :telegramLanguage,
                :registrationSource, :registrationReferral, :onboardingVariant
            )
            ON CONFLICT (telegram_id) DO NOTHING
            """.trimIndent(),
        ).param("id", telegramId)
            .param("firstName", firstName.take(128))
            .param("language", inferred)
            .param("telegramLanguage", telegramLanguage?.take(16))
            .param("registrationSource", source)
            .param("registrationReferral", referral)
            .param("onboardingVariant", onboardingVariant)
            .update()
        if (created == 0) {
            jdbc.sql(
                """
                UPDATE players
                   SET first_name = :firstName,
                       telegram_language = COALESCE(:telegramLanguage, telegram_language),
                       onboarding_variant = COALESCE(onboarding_variant, :onboardingVariant),
                       telegram_unavailable_at = NULL,
                       telegram_unavailable_reason = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE telegram_id = :id
                """.trimIndent(),
            ).param("id", telegramId)
                .param("firstName", firstName.take(128))
                .param("telegramLanguage", telegramLanguage?.take(16))
                .param("onboardingVariant", onboardingVariant)
                .update()
        } else {
            metrics.registration(source)
            journey.registerPlayer(telegramId)
        }
        inventory.ensureStarter(telegramId)
        if (created > 0) {
            journey.record(
                telegramId,
                JourneyEventType.REGISTRATION_COMPLETED,
                JourneyEventDetails(surface = "telegram", result = source, referenceId = referral),
            )
        }
    }

    private fun player(telegramId: Long, lock: Boolean = false): Player = jdbc.sql(
        """
        SELECT telegram_id, first_name, nickname, pending_nickname, language, telegram_language,
               alliance_code, commander_level, xp,
               credits, materials, command_capacity, battle_offer_version,
               victories, defeats, current_streak, best_streak,
               daily_reward_streak, daily_reward_last_claim, daily_reward_claims,
               onboarding_variant
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
            InlineKeyboardButton(GameI18n.t(language, "ratings_button"), "nav:rankings"),
            InlineKeyboardButton(GameI18n.t(language, "guide_button"), "nav:guide"),
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
                InlineKeyboardButton("${option.flag} ${option.menuName}$checked", "language:${option.code}")
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
            val text = countryChoiceText(language)
            telegram.sendMessage(chatId, text, recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)))
            journey.record(telegramId, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "country_menu"))
            return
        }
        val results = AllianceCatalog.search(query, language)
        if (results.isEmpty()) {
            telegram.sendMessage(
                chatId,
                "${GameI18n.t(language, "country_none")}\n${GameI18n.t(language, "country_lock_notice")}",
                recommendedCountryKeyboard(language, GameLanguage.fromTelegram(player.telegramLanguage)),
            )
            journey.record(telegramId, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "country_search", result = "empty"))
            return
        }
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "country_results", query)}\n${GameI18n.t(language, "country_lock_notice")}\n${GameI18n.t(language, "country_search")}",
            countryOptionsKeyboard(results),
        )
        journey.record(telegramId, JourneyEventType.ONBOARDING_COUNTRY_VIEWED, JourneyEventDetails(surface = "country_search", result = "results"))
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
        telegram.sendMessage(
            chatId,
            "${GameI18n.t(language, "country_page", page + 1, totalPages)}\n${GameI18n.t(language, "country_lock_notice")}",
            InlineKeyboardMarkup(rows),
        )
        journey.record(
            telegramId,
            JourneyEventType.ONBOARDING_COUNTRY_VIEWED,
            JourneyEventDetails(surface = "country_page", referenceId = page.toString()),
        )
    }

    private fun countryOptionsKeyboard(options: List<AllianceOption>) = InlineKeyboardMarkup(
        options.map { listOf(InlineKeyboardButton(it.label, "country:select:${it.code}")) },
    )

    private fun countryChoiceText(language: GameLanguage): String =
        "${GameI18n.t(language, "country_title")}\n\n" +
            "${GameI18n.t(language, "country_neutral")}\n" +
            "${GameI18n.t(language, "country_lock_notice")}\n\n" +
            "${GameI18n.t(language, "country_recommended")}\n" +
            GameI18n.t(language, "country_search")

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

    private fun technicalFailure(error: Throwable, fallback: TechnicalFailure): TechnicalFailure {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is SocketTimeoutException || it is TimeoutException }) return TechnicalFailure.TIMEOUT
        if (causes.any { it is TelegramDeliveryException }) return TechnicalFailure.TELEGRAM_SEND
        return fallback
    }

    private fun helpText(language: GameLanguage) = buildString {
        appendLine(GameI18n.t(language, "help"))
        appendLine("/army — ${GameI18n.t(language, "army_title")}")
        appendLine("/shop — ${GameI18n.t(language, "shop_title")}")
        appendLine("/stars — ${GameI18n.t(language, "help_buy_credits")}")
        appendLine("/paysupport — ${GameI18n.t(language, "help_payment_support")}")
        appendLine("/upgrade — ${GameI18n.t(language, "upgrade_title")}")
        appendLine("/daily — ${GameI18n.t(language, "daily")}")
        appendLine("/rankings — ${GameI18n.t(language, "help_rankings")}")
        append("/guide — ${GameI18n.t(language, "help_guide")}")
    }

    companion object {
        private const val COUNTRY_PAGE_SIZE = 10
        private const val ALLIANCE_RATING_PAGE_SIZE = 10
        private const val PERSONAL_BATTLE_ENGINE_VERSION = 10
        private val ADMIN_PAYMENT_COMMANDS = setOf("/refund", "/reject", "/ask")
        internal val GROUP_PLAYER_COMMANDS = setOf(
            "/start", "/battle", "/army", "/hangar", "/shop", "/stars", "/buycredits",
            "/paysupport", "/answer", "/upgrade", "/daily", "/profile", "/front",
            "/contribute", "/rankings", "/rating", "/ratings", "/guide", "/language",
            "/nickname", "/country", "/settings", "/help",
        )
    }

}

internal fun runAfterCommit(action: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        action()
        return
    }
    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
        override fun afterCommit() = action()
    })
}

internal fun applyWeeklyEconomyBonus(base: BattleResult, bonus: ActiveEconomyBonus?): BattleResult {
    if (bonus == null) return base
    val outcomeCredits = EconomyPolicy.applyPercent(base.outcomeCredits, bonus.creditsPercent)
    val destructionCredits = EconomyPolicy.applyPercent(base.destructionCredits, bonus.creditsPercent)
    val outcomeXp = EconomyPolicy.applyPercent(base.outcomeXp, bonus.xpPercent)
    val destructionXp = EconomyPolicy.applyPercent(base.destructionXp, bonus.xpPercent)
    return base.copy(
        outcomeCredits = outcomeCredits,
        destructionCredits = destructionCredits,
        outcomeXp = outcomeXp,
        destructionXp = destructionXp,
        credits = outcomeCredits + destructionCredits,
        xp = outcomeXp + destructionXp,
        materials = EconomyPolicy.applyPercent(base.materials, bonus.materialsPercent),
    )
}

internal fun formatProfileSections(baseTemplate: String, vararg optionalSections: String?): String =
    (listOf(baseTemplate.trimIndent()) + optionalSections.filterNotNull().filter(String::isNotBlank))
        .joinToString("\n\n")

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
    val onboardingVariant: String?,
) {
    val displayName: String get() = nickname?.takeIf(String::isNotBlank) ?: firstName
}

private data class Selection(val expectedOfferVersion: Long, val slot: Int)
private data class GroupBinding(val presetNo: Int, val version: Int)
private data class FirstMissionContext(
    val recommendationVersion: Int,
    val onboardingVariant: String,
    val offerVersion: Long,
    val offerSlot: Int,
    val presetNo: Int,
    val groupVersion: Int,
    val entryId: String,
    val objectiveId: String,
    val tactic: Tactic,
)

private data class PostBattleActionRow(
    val battleId: UUID,
    val action: PostBattleAction,
    val detailsText: String,
)

internal data class ParsedBotCommand(val command: String, val mention: String?, val argument: String)

/** Parses `/command[@mention] optional argument`. Returns null when the text is not a slash command. */
internal fun parseBotCommand(rawText: String): ParsedBotCommand? {
    val text = rawText.trim()
    if (!text.startsWith('/')) return null
    val firstToken = text.substringBefore(' ')
    val argument = if (' ' in text) text.substringAfter(' ').trim() else ""
    val mention = firstToken.substringAfter('@', "").takeIf { '@' in firstToken }
    val command = firstToken.substringBefore('@').lowercase()
    if (command == "/") return null
    return ParsedBotCommand(command, mention?.takeIf(String::isNotBlank), argument)
}
