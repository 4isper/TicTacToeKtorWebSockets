package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientLogicTest {

    @Test
    fun `the first render animates nothing`() {
        assertEquals(emptyList(), newMoveCells(previous = null, state = GameState()))
    }

    @Test
    fun `only the newly placed mark animates`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        val state = previous.copy(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X, Cell(1, 0) to PlayerSymbol.O)))
        assertEquals(listOf(Cell(1, 0)), newMoveCells(previous, state))
    }

    @Test
    fun `a board reset animates nothing`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        assertEquals(emptyList(), newMoveCells(previous, GameState()))
    }

    @Test
    fun `a win beats a move cue`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X, Cell(1, 0) to PlayerSymbol.O)))
        val state = previous.copy(
            field = Boards.fieldWith(
                listOf(
                    Cell(0, 0) to PlayerSymbol.X,
                    Cell(1, 0) to PlayerSymbol.O,
                    Cell(2, 0) to PlayerSymbol.X,
                ),
            ),
            winningPlayer = PlayerSymbol.X,
        )
        assertEquals(SoundCue.WIN, soundCue(previous, state))
    }

    @Test
    fun `a full board without a winner is a draw cue`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        val state = GameState(isBoardFull = true)
        assertEquals(SoundCue.DRAW, soundCue(previous, state))
    }

    @Test
    fun `a new mark is a move cue`() {
        val previous = GameState()
        val state = previous.copy(field = Boards.fieldWith(listOf(Cell(1, 1) to PlayerSymbol.X)))
        assertEquals(SoundCue.MOVE, soundCue(previous, state))
    }

    @Test
    fun `resets and unknown states stay silent`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        assertEquals(SoundCue.NONE, soundCue(previous, GameState())) // board reset
        assertEquals(SoundCue.NONE, soundCue(null, GameState())) // first render
    }

    @Test
    fun `round restart deadline is captured once per round`() {
        val before = GameState()
        val armed = before.copy(roundRestartInMs = 5_000)
        assertTrue(isRoundRestartTransition(before, armed))
        assertFalse(isRoundRestartTransition(armed, armed.copy(roundRestartInMs = 4_000)))
    }

    @Test
    fun `turn deadline is captured when the turn changes`() {
        val xTurn = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), playerAtTurn = PlayerSymbol.X, turnRemainingMs = 30_000)
        val oTurn = xTurn.copy(playerAtTurn = PlayerSymbol.O, turnRemainingMs = 29_000)
        assertTrue(isTurnTransition(xTurn, oTurn))
        assertFalse(isTurnTransition(oTurn, oTurn.copy(turnRemainingMs = 28_000))) // same turn: keep the deadline
        assertFalse(isTurnTransition(xTurn, xTurn)) // same turn
    }
}

private object Boards {
    fun fieldWith(moves: List<Pair<Cell, PlayerSymbol>>): List<List<PlayerSymbol?>> =
        List(3) { y -> List(3) { x -> moves.firstOrNull { it.first == Cell(x, y) }?.second } }
}
