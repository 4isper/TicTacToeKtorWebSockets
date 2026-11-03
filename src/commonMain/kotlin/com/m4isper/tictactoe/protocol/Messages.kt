package com.m4isper.tictactoe.protocol

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed interface ClientMessage {
    @Serializable
    @SerialName("make_turn")
    data class MakeTurn(val x: Int, val y: Int) : ClientMessage

    @Serializable
    @SerialName("reconnect")
    data class Reconnect(val token: String) : ClientMessage

    @Serializable
    @SerialName("request_rematch")
    data object RequestRematch : ClientMessage

    @Serializable
    @SerialName("set_name")
    data class SetName(val name: String) : ClientMessage
}

@Serializable
sealed interface ServerEvent {
    @Serializable
    @SerialName("joined")
    data class Joined(val token: String, val symbol: PlayerSymbol?) : ServerEvent

    @Serializable
    @SerialName("state")
    data class StateChanged(val state: GameState) : ServerEvent

    @Serializable
    @SerialName("error")
    data class Error(val reason: ErrorReason) : ServerEvent
}
