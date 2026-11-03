package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.ErrorReason
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

fun errorText(reason: ErrorReason): String = when (reason) {
    ErrorReason.CELL_OCCUPIED -> "Клетка занята"
    ErrorReason.NOT_YOUR_TURN -> "Сейчас не ваш ход"
    ErrorReason.ROUND_IS_OVER -> "Раунд окончен"
    ErrorReason.CELL_OUT_OF_BOUNDS -> "Клетка за пределами доски"
    ErrorReason.MALFORMED_MESSAGE -> "Непонятное сообщение"
    ErrorReason.UNKNOWN_TOKEN -> "Сессия не найдена"
    ErrorReason.ROOM_IS_FULL -> "В комнате уже два игрока"
    ErrorReason.INVALID_ROOM_NAME -> "Неверное имя комнаты"
    ErrorReason.STALE_SESSION -> "Эта вкладка больше не в игре"
    ErrorReason.INVALID_BOARD_SIZE -> "Неверный размер доски"
    ErrorReason.INVALID_WIN_LENGTH -> "Неверная длина победной линии"
    ErrorReason.ROUND_NOT_OVER -> "Раунд ещё идёт"
}
