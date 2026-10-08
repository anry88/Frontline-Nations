package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FrontBridgePolicyTest {
    private val alpha = FrontGroupReadiness(1, "Alpha", 12, reserved = false)
    private val bravo = FrontGroupReadiness(2, "Bravo", 10, reserved = false)
    private val charlie = FrontGroupReadiness(3, "Charlie", 20, reserved = true)

    @Test
    fun `bridge appears once after two completed battles and before a contribution`() {
        assertThat(FrontBridgePolicy.eligible(1, alreadyShown = false, alreadyContributed = false)).isFalse()
        assertThat(FrontBridgePolicy.eligible(2, alreadyShown = false, alreadyContributed = false)).isTrue()
        assertThat(FrontBridgePolicy.eligible(2, alreadyShown = true, alreadyContributed = false)).isFalse()
        assertThat(FrontBridgePolicy.eligible(2, alreadyShown = false, alreadyContributed = true)).isFalse()
    }

    @Test
    fun `only free groups within battle limits remain battle ready`() {
        assertThat(FrontBridgePolicy.battleReadyGroups(listOf(alpha, bravo, charlie), minimumBattleCp = 10, cpLimit = 15))
            .containsExactly(alpha, bravo)
        assertThat(FrontBridgePolicy.battleReadyGroups(listOf(alpha, bravo), 10, 15, excludingPreset = 1))
            .containsExactly(bravo)
    }

    @Test
    fun `contribution call to action requires an open front and two ready groups`() {
        assertThat(FrontBridgePolicy.bridgeAction(openForContributions = true, listOf(alpha, bravo)))
            .isEqualTo(FrontBridgeAction.CONTRIBUTE)
        assertThat(FrontBridgePolicy.bridgeAction(openForContributions = true, listOf(alpha)))
            .isEqualTo(FrontBridgeAction.FRONT)
        assertThat(FrontBridgePolicy.bridgeAction(openForContributions = false, listOf(alpha, bravo)))
            .isEqualTo(FrontBridgeAction.FRONT)
    }
}
