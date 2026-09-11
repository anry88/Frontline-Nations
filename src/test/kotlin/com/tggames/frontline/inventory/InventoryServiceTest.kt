package com.tggames.frontline.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InventoryServiceTest {
    @Test
    fun `group storage supports the maximum personal force category`() {
        assertThat(InventoryService.MAX_GROUP_SLOTS).isEqualTo(1_000)
        assertThat(InventoryService.MAX_PURCHASE_QUANTITY).isEqualTo(25)
    }
}
