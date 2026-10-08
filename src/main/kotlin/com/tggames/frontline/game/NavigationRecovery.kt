package com.tggames.frontline.game

internal enum class NavigationErrorCategory(val value: String) {
    UNKNOWN_COMMAND("unknown_command"),
    UNEXPECTED_TEXT("unexpected_text"),
    STALE_CALLBACK("stale_callback"),
}

internal enum class NavigationStage(val value: String) {
    COUNTRY("country"),
    NICKNAME_CONFIRMATION("nickname_confirmation"),
    FIRST_OPERATION("first_operation"),
    NEXT_BATTLE("next_battle"),
    ARMY_RECOVERY("army_recovery"),
}

internal enum class NavigationAction(val value: String, val callback: String) {
    CHOOSE_COUNTRY("choose_country", "nav:country"),
    CONFIRM_NICKNAME("confirm_nickname", "nickname:confirm"),
    CANCEL_NICKNAME("cancel_nickname", "nickname:cancel"),
    FIRST_OPERATION("first_operation", "nav:battle"),
    NEXT_BATTLE("next_battle", "nav:battle"),
    RESTORE_ARMY("restore_army", "nav:army"),
    DAILY("daily", "nav:daily"),
    HELP("help", "nav:guide"),
}

internal data class NavigationRecovery(val stage: NavigationStage, val actions: List<NavigationAction>) {
    val primary: NavigationAction get() = actions.first()
}

internal object NavigationRecoveryPolicy {
    fun decide(
        hasCountry: Boolean,
        hasPendingNickname: Boolean,
        completedBattles: Int,
        battleReady: Boolean,
        dailyAvailable: Boolean,
    ): NavigationRecovery = when {
        !hasCountry -> NavigationRecovery(NavigationStage.COUNTRY, listOf(NavigationAction.CHOOSE_COUNTRY, NavigationAction.HELP))
        hasPendingNickname -> NavigationRecovery(
            NavigationStage.NICKNAME_CONFIRMATION,
            listOf(NavigationAction.CONFIRM_NICKNAME, NavigationAction.CANCEL_NICKNAME, NavigationAction.HELP),
        )
        completedBattles == 0 -> NavigationRecovery(NavigationStage.FIRST_OPERATION, listOf(NavigationAction.FIRST_OPERATION, NavigationAction.HELP))
        battleReady -> NavigationRecovery(NavigationStage.NEXT_BATTLE, listOf(NavigationAction.NEXT_BATTLE, NavigationAction.HELP))
        dailyAvailable -> NavigationRecovery(
            NavigationStage.ARMY_RECOVERY,
            listOf(NavigationAction.RESTORE_ARMY, NavigationAction.DAILY, NavigationAction.HELP),
        )
        else -> NavigationRecovery(NavigationStage.ARMY_RECOVERY, listOf(NavigationAction.RESTORE_ARMY, NavigationAction.HELP))
    }
}
