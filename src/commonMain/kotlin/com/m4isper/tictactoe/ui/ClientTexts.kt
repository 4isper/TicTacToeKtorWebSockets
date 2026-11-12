package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.protocol.REACTION_EMOJIS

/** How many automatic reconnects happen before we ask the player to retry by hand. */
const val MAX_AUTO_RETRIES = 10

private const val MAX_RETRY_DELAY_MS = 5_000L
private const val STEP_RETRY_DELAY_MS = 1_000L

/** Linear backoff capped at five seconds; the attempt number is one-based. */
fun retryDelayMs(attempt: Int): Long = (attempt.coerceAtLeast(1).toLong() * STEP_RETRY_DELAY_MS).coerceAtMost(MAX_RETRY_DELAY_MS)

/** A spoken label for an icon-only reaction button, keyed by the canonical emoji list. */
fun reactionLabel(emoji: String, lang: Lang = Lang.RU): String {
    val labels = when (lang) {
        Lang.RU -> listOf("Нравится", "Сердце", "Смех", "Удивление")
        Lang.EN -> listOf("Thumbs up", "Heart", "Laughing", "Surprised")
    }
    return labels.getOrElse(REACTION_EMOJIS.indexOf(emoji)) {
        if (lang == Lang.RU) "Реакция" else "Reaction"
    }
}

fun connectingText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Подключение…"
    Lang.EN -> "Connecting…"
}

fun reconnectingText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Переподключение…"
    Lang.EN -> "Reconnecting…"
}

fun retryNowText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Повторить"
    Lang.EN -> "Retry now"
}

fun updateNoticeText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Доступно обновление — перезагрузите страницу"
    Lang.EN -> "Update available — reload"
}

fun reloadText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Перезагрузить"
    Lang.EN -> "Reload"
}

fun dismissText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Скрыть"
    Lang.EN -> "Dismiss"
}

/** A live round must not be abandoned by one careless click on the quick-play link. */
fun leaveGameText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Покинуть текущую партию и начать новую?"
    Lang.EN -> "Leave this game and start a new one?"
}

/** Resigning changes the score and the rating, so it gets its own confirmation. */
fun resignConfirmText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Сдаться? Соперник получит победу, раунд будет засчитан как поражение."
    Lang.EN -> "Resign? The opponent wins the round and it counts as a loss."
}

/** Spoken names for the two regions a screen reader navigates by. */
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

/** Page-level copy: the description and the landmarks a screen reader navigates by. */
fun pageDescription(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Крестики-нолики онлайн: играйте с другом по ссылке, смотрите партии и следите за рейтингом."
    Lang.EN -> "Play tic-tac-toe online: invite a friend by link, watch games and follow the leaderboard."
}

fun skipLinkText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Перейти к игре"
    Lang.EN -> "Skip to the game"
}

/** The three-state theme control needs a visible label, not only an icon. */
fun themeModeText(theme: ThemeMode, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> when (theme) {
        ThemeMode.AUTO -> "авто"
        ThemeMode.LIGHT -> "светлая"
        ThemeMode.DARK -> "тёмная"
    }

    Lang.EN -> when (theme) {
        ThemeMode.AUTO -> "auto"
        ThemeMode.LIGHT -> "light"
        ThemeMode.DARK -> "dark"
    }
}

/** The leaderboard's three numbers mean nothing without a legend. */
fun leaderboardLegend(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Игрок · Рейтинг · Победы : Поражения : Ничьи"
    Lang.EN -> "Player · Rating · Wins : Losses : Draws"
}

/** History rows spell out the result; a glyph alone reads as punctuation. */
fun resultText(result: String, lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> when (result) {
        "WIN" -> "Победа"
        "LOSS" -> "Поражение"
        else -> "Ничья"
    }

    Lang.EN -> when (result) {
        "WIN" -> "Win"
        "LOSS" -> "Loss"
        else -> "Draw"
    }
}

fun installText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Установить приложение"
    Lang.EN -> "Install app"
}

fun copyFailedText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Не удалось скопировать"
    Lang.EN -> "Copy failed"
}

fun okText(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "ОК"
    Lang.EN -> "OK"
}

private const val LIGHT_THEME_COLOR = "#f4f4f8"
private const val DARK_THEME_COLOR = "#14141f"

/** The two theme-color meta values: the light-scheme tag and the default dark tag. */
fun themeColor(theme: ThemeMode, prefersLight: Boolean): String = when (theme) {
    ThemeMode.AUTO -> if (prefersLight) LIGHT_THEME_COLOR else DARK_THEME_COLOR
    ThemeMode.LIGHT -> LIGHT_THEME_COLOR
    ThemeMode.DARK -> DARK_THEME_COLOR
}
