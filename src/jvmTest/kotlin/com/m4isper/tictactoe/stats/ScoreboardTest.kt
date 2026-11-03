package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreboardTest {

    @Test
    fun `results survive a store reload`() {
        val file = Files.createTempFile("scoreboard", ".json").toFile()
        try {
            Scoreboard(JsonScoreStore(file)).recordRound(
                mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"),
                winner = PlayerSymbol.X,
            )

            val reloaded = Scoreboard(JsonScoreStore(file)).top(limit = 10)

            assertEquals(2, reloaded.size)
            assertEquals("Аня", reloaded[0].name)
            assertEquals(1, reloaded[0].stats.wins)
            assertEquals(0, reloaded[0].stats.losses)
            assertEquals(0, reloaded[0].stats.draws)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a round records wins losses and draws by name`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), winner = PlayerSymbol.X)
        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), winner = null)

        val top = board.top(limit = 10).associateBy { it.name }
        val anya = top["Аня"]!!.stats
        val boris = top["Боря"]!!.stats
        assertEquals(PlayerStats(wins = 1, losses = 0, draws = 1, rating = anya.rating, history = anya.history), anya)
        assertEquals(PlayerStats(wins = 0, losses = 1, draws = 1, rating = boris.rating, history = boris.history), boris)
        assertEquals(RoundResult.DRAW, anya.history[0].result) // newest first
        assertEquals(RoundResult.WIN, anya.history[1].result)
        assertEquals("Боря", anya.history[0].opponent)
        assertEquals("Боря", anya.history[1].opponent)
    }

    @Test
    fun `unnamed players are not recorded`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(mapOf(PlayerSymbol.X to "Аня"), winner = PlayerSymbol.X)
        board.recordRound(emptyMap(), winner = null)

        assertTrue(board.top(limit = 10).none { it.name == "—" })
        assertEquals(1, board.top(limit = 10).single().stats.wins) // only the named winner
    }

    @Test
    fun `the rating follows the elo math`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), winner = PlayerSymbol.X)
        assertEquals(1016, board.profile("Аня")!!.stats.rating)
        assertEquals(984, board.profile("Боря")!!.stats.rating)
        assertEquals(1016, board.profile("Аня")!!.stats.history.single().ratingAfter)

        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), winner = null)
        // Аня (1016) drew the weaker Боря (984): a small loss each
        assertTrue(board.profile("Аня")!!.stats.rating < 1016)
        assertTrue(board.profile("Боря")!!.stats.rating > 984)
    }

    @Test
    fun `an unnamed opponent is treated as a default rated player`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(mapOf(PlayerSymbol.X to "Аня"), winner = PlayerSymbol.X)

        assertEquals(1016, board.profile("Аня")!!.stats.rating) // the unnamed side counts as 1000
    }

    @Test
    fun `top sorts by rating and falls back to the name`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))
        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), PlayerSymbol.X)
        board.recordRound(mapOf(PlayerSymbol.X to "Вера", PlayerSymbol.O to "Гоша"), PlayerSymbol.X)
        // two symmetric pairs: Аня=Вера 1016, Боря=Гоша 984 — the name breaks the ties

        assertEquals(listOf("Аня", "Вера", "Боря", "Гоша"), board.top(limit = 4).map { it.name })
        assertTrue(board.top(limit = 4)[0].stats.rating > board.top(limit = 4)[2].stats.rating)
    }

    @Test
    fun `history keeps the opponent and caps at twenty entries`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        repeat(25) { board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), PlayerSymbol.X) }

        val stats = board.profile("Аня")!!.stats
        assertEquals(25, stats.wins)
        assertEquals(20, stats.history.size)
        assertEquals(RoundResult.WIN, stats.history.first().result)
        assertEquals("Боря", stats.history.first().opponent)
        assertEquals("Аня", board.profile("Боря")!!.stats.history.first().opponent) // the loser's opponent is the winner
    }

    @Test
    fun `profile is null for unknown or blank names`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))
        board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), PlayerSymbol.X)

        assertEquals(null, board.profile("Кто-то"))
        assertEquals(null, board.profile(null))
        assertEquals(null, board.profile("  "))
        assertEquals("Аня", board.profile("Аня")!!.name)
    }

    @Test
    fun `a round records its moves and board size for the replay`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(
            mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"),
            winner = PlayerSymbol.X,
            moves = listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1)),
            boardSize = 5,
        )

        val record = board.profile("Аня")!!.stats.history.single()
        assertEquals(listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1)), record.moves)
        assertEquals(5, record.boardSize)
        assertEquals(Cell(0, 0), board.profile("Боря")!!.stats.history.single().moves.first()) // the loser keeps it too
    }

    @Test
    fun `an empty leaderboard renders as an empty list`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        assertTrue(board.top(limit = 10).isEmpty())
    }
}
