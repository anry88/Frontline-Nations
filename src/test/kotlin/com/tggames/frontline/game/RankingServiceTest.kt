package com.tggames.frontline.game

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

class RankingServiceTest {
    @Test
    fun `alliance pages and player top retain exact personal places`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:rankings;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        dataSource.connection.use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE alliance_ratings(alliance_code VARCHAR(8), english_name VARCHAR(128), rating BIGINT, wins INT, games_played INT)")
                sql.execute("INSERT INTO alliance_ratings VALUES ('RS','Serbia',30,2,3),('BR','Brazil',50,4,5),('AR','Argentina',40,3,4)")
                sql.execute("CREATE TABLE players(telegram_id BIGINT, nickname VARCHAR(128), first_name VARCHAR(128), xp BIGINT, commander_level INT)")
                (1..12).forEach { id -> sql.execute("INSERT INTO players VALUES ($id, NULL, 'P$id', ${1300 - id * 100}, $id)") }
            }
        }
        val service = RankingService(JdbcClient.create(dataSource))

        val secondAlliancePage = service.alliances(1, 2, "RS")
        assertThat(secondAlliancePage.entries.map { it.code }).containsExactly("RS")
        assertThat(secondAlliancePage.ownPlace).isEqualTo(3)

        val players = service.players(12)
        assertThat(players.top).hasSize(10)
        assertThat(players.top.first().telegramId).isEqualTo(1)
        assertThat(players.own.place).isEqualTo(12)
    }
}
