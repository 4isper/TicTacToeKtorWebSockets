package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.Elo
import com.m4isper.tictactoe.domain.PlayerSymbol
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val anya = top.getValue("Аня").stats
        val boris = top.getValue("Боря").stats
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
    fun `a round against an unnamed opponent is practice and keeps the rating`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        board.recordRound(mapOf(PlayerSymbol.X to "Аня"), winner = PlayerSymbol.X)
        board.recordRound(mapOf(PlayerSymbol.X to "Аня"), winner = null)

        val stats = board.profile("Аня")!!.stats
        assertEquals(Elo.DEFAULT, stats.rating) // a bot is not a rated opponent
        assertEquals(1, stats.wins)
        assertEquals(1, stats.draws)
        assertEquals(null, stats.history.single { it.result == RoundResult.WIN }.ratingAfter)
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
    fun `concurrent rounds do not lose a rating update`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))
        val workers = 8
        val roundsPerWorker = 10
        val rounds = workers * roundsPerWorker
        val names = mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря")
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            repeat(workers) {
                pool.execute {
                    start.await()
                    repeat(roundsPerWorker) { board.recordRound(names, winner = PlayerSymbol.X) }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "the writers did not finish")
        } finally {
            pool.shutdownNow()
        }

        var x = Elo.DEFAULT
        var o = Elo.DEFAULT
        repeat(rounds) {
            val nextX = Elo.next(x, o, 1.0)
            val nextO = Elo.next(o, x, 0.0)
            x = nextX
            o = nextO
        }
        assertEquals(rounds, board.profile("Аня")!!.stats.wins)
        assertEquals(x, board.profile("Аня")!!.stats.rating, "a concurrent round overwrote another round's rating")
        assertEquals(o, board.profile("Боря")!!.stats.rating)
    }

    @Test
    fun `a corrupt scoreboard is backed up before it is replaced`() {
        val file = Files.createTempFile("scoreboard", ".json").toFile()
        val backup = File(file.parentFile, "${file.name}.corrupt")
        try {
            file.writeText("{ this is not json")
            val store = JsonScoreStore(file)
            store.addResult("Аня", RoundResult.WIN)

            assertTrue(backup.exists(), "the corrupt scoreboard was lost without a backup")
            assertEquals("{ this is not json", backup.readText())
            assertEquals(1, store.loadAll().getValue("Аня").wins)
        } finally {
            file.delete()
            backup.delete()
        }
    }

    @Test
    fun `an empty leaderboard renders as an empty list`() {
        val board = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard", ".json").toFile()))

        assertTrue(board.top(limit = 10).isEmpty())
    }
}
