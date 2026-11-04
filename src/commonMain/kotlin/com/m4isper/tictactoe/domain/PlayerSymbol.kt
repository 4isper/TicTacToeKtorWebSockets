package com.m4isper.tictactoe.domain

enum class PlayerSymbol {
    X,
    O,
    ;

    val other: PlayerSymbol
        get() = if (this == X) O else X
}
