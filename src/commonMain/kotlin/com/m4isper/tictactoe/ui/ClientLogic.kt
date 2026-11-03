package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.game.GameState

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

enum class SoundCue { NONE, MOVE, WIN, DRAW }

fun soundCue(
    previous: GameState?,
    state: GameState,
): SoundCue {
    if (previous == null) return SoundCue.NONE
    val movesBefore = previous.field.sumOf { row -> row.count { it != null } }
    val movesNow = state.field.sumOf { row -> row.count { it != null } }
    return when {
        state.winningPlayer != null && previous.winningPlayer == null -> SoundCue.WIN
        state.isBoardFull && state.winningPlayer == null && !previous.isBoardFull -> SoundCue.DRAW
        movesNow > movesBefore -> SoundCue.MOVE
        else -> SoundCue.NONE
    }
}

/** A round restart countdown just appeared and the deadline should be captured once. */
fun isRoundRestartTransition(previous: GameState?, state: GameState): Boolean = state.roundRestartInMs != null && (previous == null || previous.roundRestartInMs == null)

/** A fresh turn began and the turn deadline should be captured once. */
fun isTurnTransition(previous: GameState?, state: GameState): Boolean = state.roundRestartInMs == null && state.turnRemainingMs != null &&
    (previous == null || previous.playerAtTurn != state.playerAtTurn || previous.turnRemainingMs == null)
