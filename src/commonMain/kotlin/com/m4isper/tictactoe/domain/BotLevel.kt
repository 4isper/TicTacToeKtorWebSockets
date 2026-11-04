package com.m4isper.tictactoe.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Practice bot strength; a game has a bot when its level is set. */
@Serializable
enum class BotLevel {
    @SerialName("easy")
    EASY,

    @SerialName("normal")
    NORMAL,

    @SerialName("hard")
    HARD,
}
