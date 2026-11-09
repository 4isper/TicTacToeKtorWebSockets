package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteScoreStoreTest {

    @Test
    fun `results survive a reload with moves and rating`() {
        val file = Files.createTempFile("scoreboard", ".db").toFile() // an empty file is a valid empty db
        try {
            SqliteScoreStore(file).apply {
                addResult(
                    "Аня",
                    RoundResult.WIN,
                    "Боря",
                    moves = listOf(Cell(0, 0), Cell(1, 0)),
                    boardSize = 5,
                    rating = 1016,
                    ratingAfter = 1016,
                )
                addResult("Аня", RoundResult.DRAW, rating = 1015, ratingAfter = 1015)
            }

            val stats = SqliteScoreStore(file).loadAll().getValue("Аня")
            assertEquals(1, stats.wins)
            assertEquals(0, stats.losses)
            assertEquals(1, stats.draws)
            assertEquals(1015, stats.rating)
            assertEquals(2, stats.history.size)
            assertEquals(RoundResult.DRAW, stats.history[0].result) // newest first
            assertNull(stats.history[0].opponent)
            assertEquals(listOf(Cell(0, 0), Cell(1, 0)), stats.history[1].moves)
            assertEquals(5, stats.history[1].boardSize)
            assertEquals(1016, stats.history[1].ratingAfter)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `rated games and head-to-head survive a reload`() {
        val file = Files.createTempFile("scoreboard", ".db").toFile()
        try {
            SqliteScoreStore(file).apply {
                addResult("Аня", RoundResult.WIN, "Боря", rating = 1016, ratingAfter = 1016)
                addResult("Аня", RoundResult.LOSS, "Боря", rating = 1000, ratingAfter = 1000)
                addResult("Аня", RoundResult.DRAW, "Вера", rating = 1002, ratingAfter = 1002)
            }

            val stats = SqliteScoreStore(file).loadAll().getValue("Аня")
            assertEquals(3, stats.ratedGames) // the Elo step must keep cooling after a restart
            assertEquals(HeadToHead(wins = 1, losses = 1), stats.opponents["Боря"])
            assertEquals(HeadToHead(draws = 1), stats.opponents["Вера"])
            assertEquals(1, stats.wins)
            assertEquals(1, stats.losses)
            assertEquals(1, stats.draws)
            assertEquals(1002, stats.rating)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `history is capped like the json store`() {
        val file = Files.createTempFile("scoreboard", ".db").toFile()
        try {
            val store = SqliteScoreStore(file)
            repeat(205) { store.addResult("Аня", RoundResult.WIN) }

            assertEquals(200, store.loadAll().getValue("Аня").history.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a legacy sqlite scoreboard is migrated on open`() {
        val file = Files.createTempFile("scoreboard", ".db").toFile()
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "CREATE TABLE players (name TEXT PRIMARY KEY, wins INTEGER NOT NULL DEFAULT 0, " +
                            "losses INTEGER NOT NULL DEFAULT 0, draws INTEGER NOT NULL DEFAULT 0, " +
                            "rating INTEGER NOT NULL DEFAULT 1000)",
                    )
                    statement.executeUpdate(
                        "CREATE TABLE history (seq INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, " +
                            "epoch_ms INTEGER NOT NULL, opponent TEXT, result TEXT NOT NULL, moves TEXT NOT NULL, " +
                            "board_size INTEGER NOT NULL, rating_after INTEGER)",
                    )
                    statement.executeUpdate("INSERT INTO players(name, wins, rating) VALUES ('Аня', 1, 1000)")
                    statement.executeUpdate(
                        "INSERT INTO history(player, epoch_ms, opponent, result, moves, board_size, rating_after) " +
                            "VALUES ('Аня', 1, 'Боря', 'WIN', '[]', 3, 1016)",
                    )
                    statement.executeUpdate(
                        "INSERT INTO history(player, epoch_ms, opponent, result, moves, board_size, rating_after) " +
                            "VALUES ('Аня', 2, 'Боря', 'LOSS', '[]', 3, 1000)",
                    )
                }
            }

            val stats = SqliteScoreStore(file).loadAll().getValue("Аня")
            assertEquals(1, stats.wins)
            assertEquals(1000, stats.rating)
            assertEquals(2, stats.history.size)
            assertEquals(2, stats.ratedGames)
            assertEquals(HeadToHead(wins = 1, losses = 1), stats.opponents["Боря"])
        } finally {
            file.delete()
        }
    }
}
