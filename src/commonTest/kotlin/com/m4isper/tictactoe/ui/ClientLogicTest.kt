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
    fun `an agreed draw is a draw cue`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        val state = previous.copy(isDrawAgreed = true)
        assertEquals(SoundCue.DRAW, soundCue(previous, state))
    }

    @Test
    fun `an agreed draw ends the round`() {
        assertTrue(GameState(isDrawAgreed = true).isRoundOver)
        assertFalse(GameState().isRoundOver)
    }

    @Test
    fun `a new mark is a move cue`() {
        val previous = GameState()
        val state = previous.copy(field = Boards.fieldWith(listOf(Cell(1, 1) to PlayerSymbol.X)))
        assertEquals(SoundCue.MOVE_OPPONENT, soundCue(previous, state)) // no symbol: a spectator's cue
    }

    @Test
    fun `the mover hears their own move differently`() {
        val previous = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val state = previous.copy(
            field = Boards.fieldWith(listOf(Cell(1, 1) to PlayerSymbol.X)),
            playerAtTurn = PlayerSymbol.O, // X just moved
        )
        assertEquals(SoundCue.MOVE_MINE, soundCue(previous, state, mySymbol = PlayerSymbol.X))
        assertEquals(SoundCue.MOVE_OPPONENT, soundCue(previous, state, mySymbol = PlayerSymbol.O))
    }

    @Test
    fun `resets and unknown states stay silent`() {
        val previous = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        assertEquals(SoundCue.NONE, soundCue(previous, GameState())) // board reset
        assertEquals(SoundCue.NONE, soundCue(null, GameState())) // first render
    }

    @Test
    fun `the clock reads as m ss and rounds up`() {
        assertEquals("2:30", clockText(150_000))
        assertEquals("0:10", clockText(9_500)) // ceil: never shows zero while time is left
        assertEquals("0:00", clockText(0))
        assertEquals("1:00", clockText(59_500))
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

    @Test
    fun `my turn in a running game asks for a background alert`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), playerAtTurn = PlayerSymbol.X)
        assertTrue(shouldAlertTurn(state, PlayerSymbol.X))
        assertFalse(shouldAlertTurn(state, PlayerSymbol.O))
        assertFalse(shouldAlertTurn(state, null)) // a spectator never moves
    }

    @Test
    fun `a finished round or a missing opponent never alerts`() {
        val won = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.X,
            winningPlayer = PlayerSymbol.X,
        )
        assertFalse(shouldAlertTurn(won, PlayerSymbol.X))
        val waiting = GameState(connectionPlayers = listOf(PlayerSymbol.X), playerAtTurn = PlayerSymbol.X)
        assertFalse(shouldAlertTurn(waiting, PlayerSymbol.X))
    }

    @Test
    fun `coordinates read as column letter and row number`() {
        assertEquals("a1", cellNotation(x = 0, y = 0))
        assertEquals("c3", cellNotation(x = 2, y = 2))
        assertEquals("e10", cellNotation(x = 4, y = 9))
    }

    @Test
    fun `the move log appends fresh moves`() {
        val before = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        val after = before.copy(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X, Cell(1, 0) to PlayerSymbol.O)))
        assertEquals(listOf(Cell(0, 0), Cell(1, 0)), updatedMoveLog(before, after, listOf(Cell(0, 0))))
    }

    @Test
    fun `an approved takeback drops the undone move`() {
        val full = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X, Cell(1, 0) to PlayerSymbol.O)))
        val reverted = full.copy(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        assertEquals(listOf(Cell(0, 0)), updatedMoveLog(full, reverted, listOf(Cell(0, 0), Cell(1, 0))))
    }

    @Test
    fun `a round reset empties the move log`() {
        val over = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)), winningPlayer = PlayerSymbol.X)
        val fresh = GameState()
        assertTrue(updatedMoveLog(over, fresh, listOf(Cell(0, 0))).isEmpty())
    }
}

private object Boards {
    fun fieldWith(moves: List<Pair<Cell, PlayerSymbol>>): List<List<PlayerSymbol?>> = List(3) { y -> List(3) { x -> moves.firstOrNull { it.first == Cell(x, y) }?.second } }
}
