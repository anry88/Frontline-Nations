package com.tggames.frontline.battle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BattleMapCatalogCoverageTest {
    @Test
    fun `all personal battlefields have distinct spatial maps`() {
        val maps = BattleMapCatalog(jacksonObjectMapper()).maps
        assertThat(maps).hasSize(24)
        assertThat(maps.map { it.id }).doesNotHaveDuplicates()
        assertThat(maps).allSatisfy { assertThat(it.objectives).hasSizeGreaterThanOrEqualTo(2) }
    }
}
