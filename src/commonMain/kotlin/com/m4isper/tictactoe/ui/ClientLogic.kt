package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.math.ceil

/** Cells that turned from empty to occupied since the previous state — the ones to animate. */
fun newMoveCells(previous: GameState?, state: GameState): List<Cell> {
    if (previous == null) return emptyList()
    return buildList {
        state.field.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell ->
                if (cell != null && previous.field.getOrNull(y)?.getOrNull(x) == null) {
                    add(Cell(x, y))
                }
            }
        }
    }
}

/** Everything one board cell shows; two equal views mean the DOM node needs no touch. */
data class CellView(
    val text: String,
    val cssClass: String,
    val ariaLabel: String,
    val disabled: Boolean,
)

/** The per-cell picture of the board; the renderer diffs these lists to update cells in place. */
fun cellViews(
    previous: GameState?,
    state: GameState,
    mySymbol: PlayerSymbol?,
    lang: Lang = Lang.RU,
): List<CellView> {
    val winning = state.winningCells.map { "${it.x},${it.y}" }.toSet()
    val fresh = newMoveCells(previous, state)
    return state.field.flatMapIndexed { y, row ->
        row.mapIndexed { x, cell ->
            CellView(
                text = when (cell) {
                    PlayerSymbol.X -> "X"
                    PlayerSymbol.O -> "O"
                    null -> ""
                },
                cssClass = buildString {
                    append("cell")
                    if ("$x,$y" in winning) append(" winning")
                    if (fresh.any { it.x == x && it.y == y }) append(" pop last-move")
                },
                ariaLabel = cellAriaText(x, y, cell, lang),
                disabled = cell != null || mySymbol == null || state.connectionPlayers.size < 2 || state.playerAtTurn != mySymbol,
            )
        }
    }
}

/** Chess-style coordinates: the column letter then the 1-based row, e.g. a1. */
fun cellNotation(x: Int, y: Int): String = "${'a' + x}${y + 1}"

/** The in-game move list: appends fresh moves, drops taken-back ones, empties on a round reset. */
fun updatedMoveLog(previous: GameState?, state: GameState, log: List<Cell>): List<Cell> {
    if (previous == null) return log
    if (state.field.size != previous.field.size) return emptyList()
    if (previous.isRoundOver && !state.isRoundOver) return emptyList()
    val undone = buildList {
        previous.field.forEachIndexed { y, row ->
            row.forEachIndexed { x, cell ->
                if (cell != null && state.field.getOrNull(y)?.getOrNull(x) == null) {
                    add(Cell(x, y))
                }
            }
        }
    }
    val kept = if (undone.isEmpty()) log else log.dropLastWhile { it in undone }
    return kept + newMoveCells(previous, state)
}

enum class SoundCue { NONE, MOVE_MINE, MOVE_OPPONENT, WIN, DRAW }

fun soundCue(
    previous: GameState?,
    state: GameState,
    mySymbol: PlayerSymbol? = null,
): SoundCue {
    if (previous == null) return SoundCue.NONE
    val movesBefore = previous.field.sumOf { row -> row.count { it != null } }
    val movesNow = state.field.sumOf { row -> row.count { it != null } }
    return when {
        state.winningPlayer != null && previous.winningPlayer == null -> SoundCue.WIN
        state.isDrawAgreed && !previous.isDrawAgreed -> SoundCue.DRAW
        state.isBoardFull && state.winningPlayer == null && !previous.isBoardFull -> SoundCue.DRAW
        movesNow > movesBefore -> if (mySymbol != null && state.playerAtTurn != mySymbol) SoundCue.MOVE_MINE else SoundCue.MOVE_OPPONENT
        else -> SoundCue.NONE
    }
}

/** A chess-clock readout: whole seconds rounded up, as m:ss. */
fun clockText(msLeft: Long): String {
    val seconds = ceil(msLeft / 1000.0).toInt()
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** A round restart countdown just appeared and the deadline should be captured once. */
fun isRoundRestartTransition(previous: GameState?, state: GameState): Boolean = state.roundRestartInMs != null && (previous == null || previous.roundRestartInMs == null)

/** A fresh turn began and the turn deadline should be captured once. */
fun isTurnTransition(previous: GameState?, state: GameState): Boolean = state.roundRestartInMs == null && state.turnRemainingMs != null &&
    (previous == null || previous.playerAtTurn != state.playerAtTurn || previous.turnRemainingMs == null)
