package com.tggames.frontline.game

object EconomyPolicy {
    const val DEFAULT_PERCENT = 100
    const val WEEKLY_VICTORY_BONUS_PERCENT = 120

    fun applyPercent(amount: Int, percent: Int): Int = (amount.toLong() * percent / 100).toInt()
    fun applyPercent(amount: Long, percent: Int): Long = amount * percent / 100
}
