package com.tggames.frontline.game

import com.tggames.frontline.i18n.GameLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AllianceCatalogTest {
    @Test
    fun `catalog contains all ISO countries and territories plus Kosovo`() {
        assertThat(AllianceCatalog.codes).hasSize(250)
        assertThat(AllianceCatalog.codes).contains("US", "BR", "PS", "XK", "AQ", "HK", "PR")
    }

    @Test
    fun `search accepts localized names English names and codes`() {
        assertThat(AllianceCatalog.search("Сербия", GameLanguage.RU).first().code).isEqualTo("RS")
        assertThat(AllianceCatalog.search("Brazil", GameLanguage.ES).first().code).isEqualTo("BR")
        assertThat(AllianceCatalog.search("xk", GameLanguage.EN).first().name).isEqualTo("Kosovo")
    }

    @Test
    fun `recommendations follow selected language`() {
        assertThat(AllianceCatalog.recommended(GameLanguage.PT).map { it.code }).startsWith("BR", "PT")
        assertThat(AllianceCatalog.recommended(GameLanguage.HI).map { it.code }).startsWith("IN")
    }
}
