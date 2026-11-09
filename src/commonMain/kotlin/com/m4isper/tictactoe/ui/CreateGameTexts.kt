package com.m4isper.tictactoe.ui

fun quickPlayText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Быстрая игра"
    Lang.EN -> "Quick play"
}

fun createGameTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Создать игру"
    Lang.EN -> "Create a game"
}

fun createGameSubmit(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Создать"
    Lang.EN -> "Create"
}

fun fieldRoomText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Комната"
    Lang.EN -> "Room"
}

fun fieldSizeText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Доска"
    Lang.EN -> "Board"
}

fun fieldWinText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Линия"
    Lang.EN -> "Win line"
}

fun fieldTurnText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Ход, с"
    Lang.EN -> "Turn, s"
}

fun fieldClockText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Часы, с"
    Lang.EN -> "Clock, s"
}

fun fieldBotText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Бот"
    Lang.EN -> "Bot"
}

fun fieldSeriesText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Серия"
    Lang.EN -> "Series"
}

fun seriesOffText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "нет"
    Lang.EN -> "none"
}

fun botNoneText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "нет"
    Lang.EN -> "none"
}

/** A bot level's display label; the wire value (easy/normal/hard) stays untouched. */
fun botLevelText(level: String, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> when (level) {
        "easy" -> "лёгкий"
        "normal" -> "средний"
        "hard" -> "сложный"
        else -> level
    }

    Lang.EN -> level
}

fun botExhibitionText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "выставка (2 бота)"
    Lang.EN -> "exhibition (2 bots)"
}

fun winPlaceholderText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "вся доска"
    Lang.EN -> "whole board"
}

fun clockOffText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "выкл"
    Lang.EN -> "off"
}
