package com.m4isper.tictactoe.domain

import kotlin.test.*

class GameRulesTest {

    @Test
    fun `row of same symbols wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { _, _ -> if (row == 1) PlayerSymbol.O else null }
        }
        assertEquals(PlayerSymbol.O, GameRules.winningPlayer(field))
    }

    @Test
    fun `column of same symbols wins`() {
        val field = GameRules.emptyField().mapIndexed { _, cells ->
            cells.mapIndexed { col, _ -> if (col == 2) PlayerSymbol.X else null }
        }
        assertEquals(PlayerSymbol.X, GameRules.winningPlayer(field))
    }

    @Test
    fun `main diagonal wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == col) PlayerSymbol.X else null }
        }
        assertEquals(PlayerSymbol.X, GameRules.winningPlayer(field))
    }

    @Test
    fun `anti diagonal wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row + col == 2) PlayerSymbol.O else null }
        }
        assertEquals(PlayerSymbol.O, GameRules.winningPlayer(field))
    }

    @Test
    fun `four by four diagonal wins`() {
        val field = GameRules.emptyField(size = 4).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == col) PlayerSymbol.X else null }
        }
        assertEquals(PlayerSymbol.X, GameRules.winningPlayer(field))
    }

    @Test
    fun `draw has no winner but the board is full`() {
        val field = listOf(
            listOf(PlayerSymbol.X, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.O, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.X, PlayerSymbol.X, PlayerSymbol.O),
        )
        assertNull(GameRules.winningPlayer(field))
        assertTrue(GameRules.isBoardFull(field))
    }

    @Test
    fun `empty field has no winner and is not full`() {
        val field = GameRules.emptyField()
        assertNull(GameRules.winningPlayer(field))
        assertFalse(GameRules.isBoardFull(field))
    }
}
