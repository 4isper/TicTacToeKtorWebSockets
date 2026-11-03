package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState

fun statusText(
    state: GameState,
    mySymbol: PlayerSymbol?,
): String = when {
    state.winningPlayer != null -> "Победил ${state.winningPlayer}!"
    state.isBoardFull -> "Ничья!"
    state.connectionPlayers.size < 2 -> "Ожидание соперника…"
    state.playerAtTurn == mySymbol -> "Ваш ход"
    else -> "Ход: ${state.playerAtTurn}"
}

fun scoreText(state: GameState): String {
    val x = state.score[PlayerSymbol.X] ?: 0
    val o = state.score[PlayerSymbol.O] ?: 0
    return "Счёт — X $x : $o O"
}
