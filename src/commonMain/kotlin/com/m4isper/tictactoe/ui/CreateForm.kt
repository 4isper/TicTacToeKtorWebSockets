package com.m4isper.tictactoe.ui

/** Refuse a win line that cannot fit the board before the browser navigates away. */
fun createFormError(size: Int?, win: Int?, lang: Lang = Lang.RU): String? = when {
    size == null || win == null -> null

    // blank fields fall back to the server defaults
    win > size -> when (lang) {
        Lang.RU -> "Линия не может быть длиннее доски"
        Lang.EN -> "Win line cannot exceed the board"
    }

    else -> null
}
