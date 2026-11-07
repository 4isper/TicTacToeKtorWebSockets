package com.m4isper.tictactoe.ui

/** The round action buttons — rematch, draw, takeback, resign and the hint — speak here. */
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
fun resignButtonText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Сдаться"
    Lang.EN -> "Resign"
}
fun hintText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Подсказка"
    Lang.EN -> "Hint"
}
fun chatTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Чат"
    Lang.EN -> "Chat"
}
fun chatPlaceholder(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Сообщение…"
    Lang.EN -> "Message…"
}
fun chatSendText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Отправить"
    Lang.EN -> "Send"
}
fun spectatorText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Зритель"
    Lang.EN -> "Spectator"
}
