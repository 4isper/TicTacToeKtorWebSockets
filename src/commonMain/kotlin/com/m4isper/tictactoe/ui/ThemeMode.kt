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

fun themeIcon(theme: ThemeMode): String = when (theme) {
    ThemeMode.AUTO -> "🌓"
    ThemeMode.LIGHT -> "☀️"
    ThemeMode.DARK -> "🌙"
}

fun themeTitle(theme: ThemeMode, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> when (theme) {
        ThemeMode.AUTO -> "Тема: как в системе — включить светлую"
        ThemeMode.LIGHT -> "Тема: светлая — включить тёмную"
        ThemeMode.DARK -> "Тема: тёмная — вернуть как в системе"
    }

    Lang.EN -> when (theme) {
        ThemeMode.AUTO -> "Theme: follow the system — switch to light"
        ThemeMode.LIGHT -> "Theme: light — switch to dark"
        ThemeMode.DARK -> "Theme: dark — follow the system"
    }
}
