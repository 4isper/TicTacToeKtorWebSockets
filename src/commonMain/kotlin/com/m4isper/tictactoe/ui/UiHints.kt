package com.m4isper.tictactoe.ui

/** Spoken names, legends and short hints: the copy a UI needs beyond its status lines. */

fun boardLabel(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Игровое поле"
    Lang.EN -> "Game board"
}

fun moveListLabel(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Список ходов"
    Lang.EN -> "Move list"
}

fun replayPrevText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Предыдущий ход"
    Lang.EN -> "Previous move"
}

fun replayNextText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Следующий ход"
    Lang.EN -> "Next move"
}

fun pageDescription(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Крестики-нолики онлайн: играйте с другом по ссылке, смотрите партии и следите за рейтингом."
    Lang.EN -> "Play tic-tac-toe online: invite a friend by link, watch games and follow the leaderboard."
}

fun skipLinkText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Перейти к игре"
    Lang.EN -> "Skip to the game"
}

/** Spoken names for the language switch; the visible labels are the ISO-style codes. */
fun languageName(lang: Lang): String = when (lang) {
    Lang.RU -> "Русский"
    Lang.EN -> "English"
}

fun leaderboardLegend(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Игрок · Рейтинг · Победы : Поражения : Ничьи"
    Lang.EN -> "Player · Rating · Wins : Losses : Draws"
}

fun advancedSettingsText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Дополнительные настройки"
    Lang.EN -> "Advanced settings"
}

fun winRuleHintText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Линия не длиннее доски"
    Lang.EN -> "Win line ≤ board"
}

fun scrollBoardText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Прокрутите поле вправо →"
    Lang.EN -> "Scroll the board sideways →"
}

fun chatEnterHintText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Enter — отправить"
    Lang.EN -> "Enter to send"
}
