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

    @Test
    fun `a cell view renders the mark and disables taken cells`() {
        val state = GameState(
            field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)),
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.O,
        )
        val views = cellViews(previous = null, state, mySymbol = PlayerSymbol.O)
        assertEquals(9, views.size)
        assertEquals("X", views[0].text)
        assertTrue(views[0].disabled)
        assertEquals("", views[4].text)
        assertFalse(views[4].disabled) // my turn: empty cells are clickable
    }

    @Test
    fun `a spectator and a waiting player can click nothing`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X), playerAtTurn = PlayerSymbol.X)
        assertTrue(cellViews(null, state, mySymbol = null).all { it.disabled }) // spectator
        assertTrue(cellViews(null, state, mySymbol = PlayerSymbol.O).all { it.disabled }) // opponent slot empty
        assertTrue(cellViews(null, state, mySymbol = PlayerSymbol.X).all { it.disabled }) // no opponent yet
    }

    @Test
    fun `the fresh move pops until the next state`() {
        val before = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val moved = before.copy(field = Boards.fieldWith(listOf(Cell(1, 1) to PlayerSymbol.X)), playerAtTurn = PlayerSymbol.O)
        val after = moved.copy(field = Boards.fieldWith(listOf(Cell(1, 1) to PlayerSymbol.X, Cell(0, 0) to PlayerSymbol.O)), playerAtTurn = PlayerSymbol.X)
        assertEquals("cell pop last-move", cellViews(before, moved, PlayerSymbol.O)[4].cssClass)
        assertEquals("cell", cellViews(moved, after, PlayerSymbol.X)[4].cssClass) // an older stone pops no more
    }

    @Test
    fun `winning cells carry the winning class`() {
        val field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X, Cell(1, 0) to PlayerSymbol.X, Cell(2, 0) to PlayerSymbol.X))
        val state = GameState(field = field, winningPlayer = PlayerSymbol.X, winningCells = listOf(Cell(0, 0), Cell(1, 0), Cell(2, 0)))
        val views = cellViews(null, state, mySymbol = null)
        assertEquals("cell winning", views[0].cssClass)
        assertEquals("cell winning", views[1].cssClass)
        assertEquals("cell", views[5].cssClass)
    }

    @Test
    fun `a move changes exactly one cell view`() {
        val before = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val after = before.copy(field = Boards.fieldWith(listOf(Cell(2, 1) to PlayerSymbol.X)), playerAtTurn = PlayerSymbol.O)
        val beforeViews = cellViews(before, before, PlayerSymbol.O)
        val afterViews = cellViews(before, after, PlayerSymbol.O)
        val changedContent = beforeViews.zip(afterViews).count { (old, new) -> old.text != new.text || old.cssClass != new.cssClass }
        assertEquals(1, changedContent)
        assertFalse(afterViews[7].disabled)
        assertTrue(beforeViews[7].disabled)
    }

    @Test
    fun `the aria label follows the language`() {
        val state = GameState(field = Boards.fieldWith(listOf(Cell(0, 0) to PlayerSymbol.X)))
        assertTrue(cellViews(null, state, null, Lang.RU)[0].ariaLabel.startsWith("Ряд 1"))
        assertTrue(cellViews(null, state, null, Lang.EN)[0].ariaLabel.startsWith("Row 1"))
    }
}

private object Boards {
    fun fieldWith(moves: List<Pair<Cell, PlayerSymbol>>): List<List<PlayerSymbol?>> = List(3) { y -> List(3) { x -> moves.firstOrNull { it.first == Cell(x, y) }?.second } }
}
