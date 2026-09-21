package com.tggames.frontline.campaign

import com.tggames.frontline.battle.CombatGroupSnapshot
import com.tggames.frontline.battle.Tactic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class FrontReservationBlockersTest {
    @Test
    fun `reports the other front groups that reserve shared equipment`() {
        val alphaUnit = UUID.randomUUID()
        val bravoOnlyUnit = UUID.randomUUID()
        val charlieUnit = UUID.randomUUID()
        val contributions = listOf(
            contribution(3, "Charlie", charlieUnit),
            contribution(1, "Alpha", alphaUnit),
        )

        val blockers = frontReservationBlockers(
            presetNo = 2,
            unitIds = listOf(alphaUnit, bravoOnlyUnit, charlieUnit),
            contributions = contributions,
        )

        assertThat(blockers).containsExactly("Alpha", "Charlie")
    }

    @Test
    fun `does not treat the contribution being replaced as a blocker`() {
        val sharedUnit = UUID.randomUUID()

        val blockers = frontReservationBlockers(
            presetNo = 2,
            unitIds = listOf(sharedUnit),
            contributions = listOf(contribution(2, "Bravo", sharedUnit)),
        )

        assertThat(blockers).isEmpty()
    }

    private fun contribution(presetNo: Int, name: String, vararg unitIds: UUID) = FrontContribution(
        id = presetNo.toLong(),
        presetNo = presetNo,
        groupName = name,
        snapshot = CombatGroupSnapshot(UUID.randomUUID(), 1, 10, emptyList()),
        unitIds = unitIds.toList(),
        entryId = null,
        tactic = Tactic.MANEUVER,
        primaryObjectiveId = null,
    )
}
