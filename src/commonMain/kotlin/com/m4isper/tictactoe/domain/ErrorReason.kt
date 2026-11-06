package com.m4isper.tictactoe.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorReason {
    @SerialName("cell occupied")
    CELL_OCCUPIED,

    @SerialName("not your turn")
    NOT_YOUR_TURN,

    @SerialName("round is over")
    ROUND_IS_OVER,

    @SerialName("cell out of bounds")
    CELL_OUT_OF_BOUNDS,

    @SerialName("malformed message")
    MALFORMED_MESSAGE,

    @SerialName("unknown token")
    UNKNOWN_TOKEN,

    @SerialName("room is full")
    ROOM_IS_FULL,

    @SerialName("invalid room name")
    INVALID_ROOM_NAME,

    @SerialName("stale session")
    STALE_SESSION,

    @SerialName("invalid board size")
    INVALID_BOARD_SIZE,

    @SerialName("invalid win length")
    INVALID_WIN_LENGTH,

    @SerialName("round is not over")
    ROUND_NOT_OVER,

    @SerialName("invalid name")
    INVALID_NAME,

    @SerialName("name is taken")
    NAME_TAKEN,

    @SerialName("invalid turn time")
    INVALID_TURN_TIME,

    @SerialName("invalid bot level")
    INVALID_BOT_LEVEL,

    @SerialName("invalid reaction")
    INVALID_REACTION,

    @SerialName("nothing to undo")
    NOTHING_TO_UNDO,

    @SerialName("invalid clock")
    INVALID_CLOCK,

    @SerialName("invalid wins")
    INVALID_WINS,
}
