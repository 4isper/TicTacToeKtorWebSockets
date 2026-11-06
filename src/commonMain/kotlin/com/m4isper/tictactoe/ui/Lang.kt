package com.m4isper.tictactoe.ui

enum class Lang {
    RU,
    EN,
}

fun parseLang(raw: String?): Lang = if (raw.equals("en", ignoreCase = true)) Lang.EN else Lang.RU

/**
 * Picks the language from an Accept-Language header (or a single navigator.language tag):
 * the first recognized tag wins, anything unrecognized is skipped, RU by default.
 */
fun detectLang(raw: String?): Lang {
    val tags = raw?.lowercase()?.split(",").orEmpty()
    for (tag in tags) {
        when (tag.substringBefore(";").trim().take(2)) {
            "en" -> return Lang.EN
            "ru" -> return Lang.RU
        }
    }
    return Lang.RU
}
