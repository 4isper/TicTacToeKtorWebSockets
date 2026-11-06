package com.m4isper.tictactoe.ui

enum class ThemeMode {
    AUTO,
    LIGHT,
    DARK,
}

fun parseTheme(raw: String?): ThemeMode = when (raw) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    else -> ThemeMode.AUTO
}
