package com.tggames.frontline.game

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service

data class AllianceRank(val place: Int, val code: String, val rating: Long, val wins: Int, val games: Int)
data class AllianceRankingPage(val page: Int, val pages: Int, val entries: List<AllianceRank>, val ownPlace: Int?)
data class PlayerRank(val place: Int, val telegramId: Long, val name: String, val xp: Long, val level: Int)
data class PlayerRanking(val top: List<PlayerRank>, val own: PlayerRank)

@Service
class RankingService(private val jdbc: JdbcClient) {
    fun alliances(page: Int, pageSize: Int, ownCode: String?): AllianceRankingPage {
        val total = jdbc.sql("SELECT COUNT(*) FROM alliance_ratings").query(Int::class.java).single()
        val pages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
        val normalized = page.coerceIn(0, pages - 1)
        val entries = jdbc.sql(
            """
            SELECT ranked.place, ranked.alliance_code, ranked.rating, ranked.wins, ranked.games_played
              FROM (
                    SELECT ROW_NUMBER() OVER (ORDER BY rating DESC, english_name, alliance_code) AS place,
                           alliance_code, rating, wins, games_played
                      FROM alliance_ratings
                   ) ranked
             ORDER BY ranked.place
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        ).param("limit", pageSize).param("offset", normalized * pageSize)
            .query { rs, _ -> AllianceRank(rs.getInt("place"), rs.getString("alliance_code"), rs.getLong("rating"), rs.getInt("wins"), rs.getInt("games_played")) }
            .list()
        val ownPlace = ownCode?.let { code ->
            jdbc.sql(
                """
                SELECT place FROM (
                    SELECT ROW_NUMBER() OVER (ORDER BY rating DESC, english_name, alliance_code) AS place, alliance_code
                      FROM alliance_ratings
                ) ranked WHERE alliance_code = :code
                """.trimIndent(),
            ).param("code", code).query(Int::class.java).optional().orElse(null)
        }
        return AllianceRankingPage(normalized, pages, entries, ownPlace)
    }

    fun players(playerId: Long, limit: Int = 10): PlayerRanking {
        val query = """
            SELECT ranked.place, ranked.telegram_id, ranked.display_name, ranked.xp, ranked.commander_level
              FROM (
                    SELECT ROW_NUMBER() OVER (ORDER BY xp DESC, telegram_id) AS place,
                           telegram_id, COALESCE(NULLIF(nickname, ''), first_name) AS display_name,
                           xp, commander_level
                      FROM players
                   ) ranked
        """.trimIndent()
        val top = jdbc.sql("$query ORDER BY ranked.place LIMIT :limit")
            .param("limit", limit).query(::mapPlayer).list()
        val own = jdbc.sql("$query WHERE ranked.telegram_id = :player")
            .param("player", playerId).query(::mapPlayer).single()
        return PlayerRanking(top, own)
    }

    private fun mapPlayer(rs: java.sql.ResultSet, row: Int) = PlayerRank(
        rs.getInt("place"), rs.getLong("telegram_id"), rs.getString("display_name"), rs.getLong("xp"), rs.getInt("commander_level"),
    )
}
