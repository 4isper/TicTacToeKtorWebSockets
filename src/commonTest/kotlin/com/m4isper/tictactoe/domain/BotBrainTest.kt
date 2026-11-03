package com.m4isper.tictactoe.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotBrainTest {

    @Test
    fun `bot takes the center of an empty three by three`() {
        assertEquals(Cell(1, 1), BotBrain.chooseMove(GameRules.emptyField(), PlayerSymbol.X, winLength = 3))
    }

    @Test
    fun `bot takes an immediate win`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col == 0 && row <= 1) PlayerSymbol.X else null }
        }
        assertEquals(Cell(0, 2), BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 3))
    }

    @Test
    fun `bot blocks an immediate loss`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col == 1 && row <= 1) PlayerSymbol.O else null }
        }
        assertEquals(Cell(1, 2), BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 3))
    }

    @Test
    fun `bot prefers winning over blocking`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ ->
                when {
                    col == 0 && row <= 1 -> PlayerSymbol.X

                    // bot's winning line
                    col == 2 && row <= 1 -> PlayerSymbol.O

                    // opponent's threat
                    else -> null
                }
            }
        }
        assertEquals(Cell(0, 2), BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 3))
    }

    @Test
    fun `bot blocks a diagonal threat`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == col && row <= 1) PlayerSymbol.O else null }
        }
        assertEquals(Cell(2, 2), BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 3))
    }

    @Test
    fun `bot blocks a row fork`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ ->
                when {
                    row == 0 && col == 0 -> PlayerSymbol.X
                    row == 0 && col == 2 -> PlayerSymbol.X
                    row == 1 && col == 1 -> PlayerSymbol.O
                    else -> null
                }
            }
        }
        assertEquals(Cell(1, 0), BotBrain.chooseMove(field, PlayerSymbol.O, winLength = 3))
    }

    @Test
    fun `bot picks an empty in-bounds cell on a busy five by five`() {
        val field = GameRules.emptyField(size = 5).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == 2 && col == 2) PlayerSymbol.O else null }
        }
        val move = assertNotNull(BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 4))
        assertNull(field[move.y][move.x])
        assertTrue(move.x in 0..4 && move.y in 0..4)
    }

    @Test
    fun `bot answers on a sparse ten by ten`() {
        val field = GameRules.emptyField(size = 10).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == 5 && col == 5) PlayerSymbol.O else null }
        }
        val move = assertNotNull(BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 5))
        assertNull(field[move.y][move.x])
    }

    @Test
    fun `bot returns null only on a full board`() {
        val field = listOf(
            listOf(PlayerSymbol.X, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.O, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.X, PlayerSymbol.X, PlayerSymbol.O),
        )
        assertNull(BotBrain.chooseMove(field, PlayerSymbol.X, winLength = 3))
    }
}
