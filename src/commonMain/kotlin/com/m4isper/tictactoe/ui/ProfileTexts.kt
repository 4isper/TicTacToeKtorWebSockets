package com.m4isper.tictactoe.ui

fun searchPlaceholder(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Найти игрока"
    Lang.EN -> "Find a player"
}

fun profileNotFoundText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Игрок не найден"
    Lang.EN -> "Player not found"
}

fun headToHeadTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Соперники"
    Lang.EN -> "Head to head"
}
