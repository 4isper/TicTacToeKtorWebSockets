package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.math.ceil

fun statusText(
    state: GameState,
    mySymbol: PlayerSymbol?,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> when {
        state.winningPlayer != null -> "Победил ${displayName(state, state.winningPlayer)}!"
        state.isDrawAgreed -> "Ничья по соглашению!"
        state.isBoardFull -> "Ничья!"
        state.connectionPlayers.size < 2 -> "Ожидание соперника…"
        state.playerAtTurn == mySymbol -> "Ваш ход"
        else -> "Ход: ${displayName(state, state.playerAtTurn)}"
    }

    Lang.EN -> when {
        state.winningPlayer != null -> "${displayName(state, state.winningPlayer)} won!"
        state.isDrawAgreed -> "Draw by agreement!"
        state.isBoardFull -> "Draw!"
        state.connectionPlayers.size < 2 -> "Waiting for the opponent…"
        state.playerAtTurn == mySymbol -> "Your move"
        else -> "Turn: ${displayName(state, state.playerAtTurn)}"
    }
}

private fun displayName(state: GameState, symbol: PlayerSymbol?): String = symbol?.let { state.playerNames[it] ?: it.toString() } ?: "—"

fun scoreText(
    state: GameState,
    lang: Lang = Lang.RU,
): String {
    val x = state.score[PlayerSymbol.X] ?: 0
    val o = state.score[PlayerSymbol.O] ?: 0
    val label = when (lang) {
        Lang.RU -> "Счёт"
        Lang.EN -> "Score"
    }
    return "$label — X $x : $o O"
}

fun errorText(
    reason: ErrorReason,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> when (reason) {
        ErrorReason.CELL_OCCUPIED -> "Клетка занята"
        ErrorReason.NOT_YOUR_TURN -> "Сейчас не ваш ход"
        ErrorReason.ROUND_IS_OVER -> "Раунд окончен"
        ErrorReason.CELL_OUT_OF_BOUNDS -> "Клетка за пределами доски"
        ErrorReason.MALFORMED_MESSAGE -> "Непонятное сообщение"
        ErrorReason.UNKNOWN_TOKEN -> "Сессия не найдена"
        ErrorReason.ROOM_IS_FULL -> "Нет свободных игр — создайте комнату и пригласите друга"
        ErrorReason.INVALID_ROOM_NAME -> "Неверное имя комнаты"
        ErrorReason.STALE_SESSION -> "Эта вкладка больше не в игре"
        ErrorReason.INVALID_BOARD_SIZE -> "Неверный размер доски"
        ErrorReason.INVALID_WIN_LENGTH -> "Неверная длина победной линии"
        ErrorReason.ROUND_NOT_OVER -> "Раунд ещё идёт"
        ErrorReason.INVALID_NAME -> "Неверное имя"
        ErrorReason.NAME_TAKEN -> "Это имя уже занято"
        ErrorReason.INVALID_TURN_TIME -> "Неверное время хода"
        ErrorReason.INVALID_BOT_LEVEL -> "Неверный уровень бота"
        ErrorReason.INVALID_REACTION -> "Неизвестная реакция"
        ErrorReason.NOTHING_TO_UNDO -> "Отменять нечего"
        ErrorReason.INVALID_CLOCK -> "Неверное время на партию"
    }

    Lang.EN -> when (reason) {
        ErrorReason.CELL_OCCUPIED -> "Cell occupied"
        ErrorReason.NOT_YOUR_TURN -> "Not your turn"
        ErrorReason.ROUND_IS_OVER -> "Round is over"
        ErrorReason.CELL_OUT_OF_BOUNDS -> "Cell out of bounds"
        ErrorReason.MALFORMED_MESSAGE -> "Malformed message"
        ErrorReason.UNKNOWN_TOKEN -> "Session not found"
        ErrorReason.ROOM_IS_FULL -> "No open games — create a room and invite a friend"
        ErrorReason.INVALID_ROOM_NAME -> "Invalid room name"
        ErrorReason.STALE_SESSION -> "This tab is no longer in the game"
        ErrorReason.INVALID_BOARD_SIZE -> "Invalid board size"
        ErrorReason.INVALID_WIN_LENGTH -> "Invalid win length"
        ErrorReason.ROUND_NOT_OVER -> "Round is not over"
        ErrorReason.INVALID_NAME -> "Invalid name"
        ErrorReason.NAME_TAKEN -> "Name already taken"
        ErrorReason.INVALID_TURN_TIME -> "Invalid turn time"
        ErrorReason.INVALID_BOT_LEVEL -> "Invalid bot level"
        ErrorReason.INVALID_REACTION -> "Unknown reaction"
        ErrorReason.NOTHING_TO_UNDO -> "Nothing to undo"
        ErrorReason.INVALID_CLOCK -> "Invalid game clock"
    }
}

fun restartText(
    msLeft: Long,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> "Новый раунд через ${ceil(msLeft / 1000.0).toInt()}…"
    Lang.EN -> "Next round in ${ceil(msLeft / 1000.0).toInt()}…"
}

fun timerSuffix(
    msLeft: Long,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> " · ${ceil(msLeft / 1000.0).toInt()} с"
    Lang.EN -> " · ${ceil(msLeft / 1000.0).toInt()}s"
}

fun rematchButtonText(
    waiting: Boolean,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> if (waiting) "Ожидание соперника…" else "Играть снова"
    Lang.EN -> if (waiting) "Waiting for the opponent…" else "Play again"
}

fun drawButtonText(
    iOffered: Boolean,
    opponentOffered: Boolean,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> when {
        opponentOffered -> "Принять ничью"
        iOffered -> "Предложение отправлено…"
        else -> "Предложить ничью"
    }

    Lang.EN -> when {
        opponentOffered -> "Accept draw"
        iOffered -> "Offer sent…"
        else -> "Offer a draw"
    }
}

fun undoButtonText(
    iOffered: Boolean,
    opponentOffered: Boolean,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> when {
        opponentOffered -> "Разрешить отмену"
        iOffered -> "Ожидание согласия…"
        else -> "Отменить ход"
    }

    Lang.EN -> when {
        opponentOffered -> "Allow the takeback"
        iOffered -> "Waiting for approval…"
        else -> "Undo my move"
    }
}

fun copyLinkText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Скопировать ссылку"
    Lang.EN -> "Copy link"
}

fun copiedText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Скопировано!"
    Lang.EN -> "Copied!"
}

fun namePlaceholder(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Ваше имя"
    Lang.EN -> "Your name"
}

fun leaderboardTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Зал славы"
    Lang.EN -> "Leaderboard"
}

fun roomsTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Открытые комнаты"
    Lang.EN -> "Open rooms"
}

fun noRoomsText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Пока нет открытых комнат — создайте свою по ссылке"
    Lang.EN -> "No open rooms yet — create one with a link"
}

fun botLabel(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "бот"
    Lang.EN -> "bot"
}

fun turnSecondsText(seconds: Int, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "$seconds с"
    Lang.EN -> "${seconds}s"
}

fun replayHintText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Показать партию"
    Lang.EN -> "Replay this match"
}

fun cellAriaText(
    x: Int,
    y: Int,
    symbol: PlayerSymbol?,
    lang: Lang = Lang.RU,
): String = when (lang) {
    Lang.RU -> when (symbol) {
        PlayerSymbol.X -> "Ряд ${y + 1}, колонка ${x + 1}: X"
        PlayerSymbol.O -> "Ряд ${y + 1}, колонка ${x + 1}: O"
        null -> "Ряд ${y + 1}, колонка ${x + 1}, пусто"
    }

    Lang.EN -> when (symbol) {
        PlayerSymbol.X -> "Row ${y + 1}, column ${x + 1}: X"
        PlayerSymbol.O -> "Row ${y + 1}, column ${x + 1}: O"
        null -> "Row ${y + 1}, column ${x + 1}, empty"
    }
}

fun soundIcon(muted: Boolean): String = if (muted) "🔇" else "🔊"

fun soundTitle(muted: Boolean, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> if (muted) "Включить звук" else "Выключить звук"
    Lang.EN -> if (muted) "Unmute" else "Mute"
}

fun connectionLostText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Соединение потеряно, переподключение…"
    Lang.EN -> "Connection lost, reconnecting…"
}

fun connectionErrorText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Ошибка соединения"
    Lang.EN -> "Connection error"
}
