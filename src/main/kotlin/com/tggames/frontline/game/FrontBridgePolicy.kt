package com.tggames.frontline.game

internal data class FrontGroupReadiness(
    val presetNo: Int,
    val name: String,
    val usedCp: Int,
    val reserved: Boolean,
)

internal enum class FrontBridgeAction(val value: String) {
    CONTRIBUTE("contribute"),
    FRONT("front"),
}

internal object FrontBridgePolicy {
    const val MIN_COMPLETED_BATTLES = 2

    fun eligible(completedBattles: Int, alreadyShown: Boolean, alreadyContributed: Boolean): Boolean =
        completedBattles >= MIN_COMPLETED_BATTLES && !alreadyShown && !alreadyContributed

    fun battleReadyGroups(
        groups: List<FrontGroupReadiness>,
        minimumBattleCp: Int,
        cpLimit: Int,
        excludingPreset: Int? = null,
    ): List<FrontGroupReadiness> = groups.filter { group ->
        group.presetNo != excludingPreset &&
            !group.reserved &&
            group.usedCp in minimumBattleCp..cpLimit
    }

    fun bridgeAction(openForContributions: Boolean, readyGroups: List<FrontGroupReadiness>): FrontBridgeAction =
        if (openForContributions && readyGroups.size >= 2) FrontBridgeAction.CONTRIBUTE else FrontBridgeAction.FRONT
}

internal data class FrontBridgeOffer(
    val text: String,
    val buttonText: String,
    val action: FrontBridgeAction,
    val recordShown: () -> Unit,
)
