package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import java.nio.file.Files
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
    fun `history is capped at twenty`() {
        val file = Files.createTempFile("scoreboard", ".db").toFile()
        try {
            val store = SqliteScoreStore(file)
            repeat(25) { store.addResult("Аня", RoundResult.WIN) }

            assertEquals(20, store.loadAll().getValue("Аня").history.size)
        } finally {
            file.delete()
        }
    }
}
