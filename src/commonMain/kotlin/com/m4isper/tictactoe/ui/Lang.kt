package com.m4isper.tictactoe.ui

enum class Lang {
    RU,
    EN,
}

fun parseLang(raw: String?): Lang = if (raw.equals("en", ignoreCase = true)) Lang.EN else Lang.RU
