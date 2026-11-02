package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.models.GameState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("make_turn")
    data class MakeTurn(val x: Int, val y: Int) : ClientMessage
}

@Serializable
sealed interface ServerEvent {
    @Serializable
    @SerialName("state")
    data class StateChanged(val state: GameState) : ServerEvent

    @Serializable
    @SerialName("error")
    data class MoveRejected(val reason: String) : ServerEvent
}
