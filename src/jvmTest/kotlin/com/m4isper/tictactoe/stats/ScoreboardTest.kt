package com.m4isper.tictactoe.stats

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
        assertEquals(PlayerStats(wins = 1, losses = 0, draws = 1), top["Аня"]?.stats)
        assertEquals(PlayerStats(wins = 0, losses = 1, draws = 1), top["Боря"]?.stats)
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
    fun `top sorts by wins and respects the limit`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))
        repeat(3) { board.recordRound(mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"), PlayerSymbol.X) }
        repeat(2) { board.recordRound(mapOf(PlayerSymbol.X to "Вера", PlayerSymbol.O to "Аня"), PlayerSymbol.X) }

        val top = board.top(limit = 2)

        assertEquals(listOf("Аня", "Вера"), top.map { it.name })
        assertEquals(3, top[0].stats.wins)
        assertEquals(2, top[1].stats.wins)
    }

    @Test
    fun `an empty leaderboard renders as an empty list`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        assertTrue(board.top(limit = 10).isEmpty())
    }
}
