package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionVisibilityTest {

    @Test
    fun `a takeback needs a move to take back`() {
        val opening = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.X,
        )
        assertFalse(undoAvailable(opening, PlayerSymbol.O))
        assertFalse(undoAvailable(opening, PlayerSymbol.X))
        assertFalse(undoAvailable(opening, null))
    }

    @Test
    fun `a takeback is offered to the side that just moved`() {
        val moved = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.O,
            moves = listOf(Cell(0, 0)),
        )
        assertTrue(undoAvailable(moved, PlayerSymbol.X))
        assertFalse(undoAvailable(moved, PlayerSymbol.O))
    }

    @Test
    fun `a finished round offers no takeback`() {
        val won = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.O,
            moves = listOf(Cell(0, 0)),
            winningPlayer = PlayerSymbol.X,
        )
        assertFalse(undoAvailable(won, PlayerSymbol.X))
    }

    @Test
    fun `a hint needs a bot, not just two unnamed humans`() {
        val pvp = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.X,
        )
        assertFalse(hintAvailable(pvp, PlayerSymbol.X))
        val practice = pvp.copy(botPlayers = listOf(PlayerSymbol.O))
        assertTrue(hintAvailable(practice, PlayerSymbol.X))
    }

    @Test
    fun `even a bot round hides the hint when both sides are named`() {
        val rated = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.X,
            botPlayers = listOf(PlayerSymbol.O),
            playerNames = mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"),
        )
        assertFalse(hintAvailable(rated, PlayerSymbol.X))
    }
}
