package com.tggames.frontline.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InventoryServiceTest {
    @Test
    fun `command point growth is slow and bounded`() {
        assertThat(InventoryService.cpLimit(1)).isEqualTo(10)
        assertThat(InventoryService.cpLimit(10)).isEqualTo(10)
        assertThat(InventoryService.cpLimit(11)).isEqualTo(11)
        assertThat(InventoryService.cpLimit(50)).isEqualTo(14)
        assertThat(InventoryService.cpLimit(999)).isEqualTo(14)
    }
}
