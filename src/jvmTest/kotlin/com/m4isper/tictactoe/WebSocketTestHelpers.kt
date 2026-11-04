package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.stats.JsonScoreStore
import com.m4isper.tictactoe.stats.Scoreboard
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.*

/** A module whose scoreboard lives in a temp file, so the tests never touch the real leaderboard. */
internal fun Application.testModule(reconnectGraceMs: Long = 10_000L) = module(
    scoreboard = Scoreboard(JsonScoreStore(Files.createTempFile("scoreboard-test", ".json").toFile())),
    reconnectGraceMs = reconnectGraceMs,
)

internal suspend fun WebSocketSession.skipBroadcast() {
    receiveEvent()
}

internal suspend fun WebSocketSession.receiveEvent(): ServerEvent = withTimeout(5_000) {
    protocolJson.decodeFromString<ServerEvent>((incoming.receive() as Frame.Text).readText())
}

internal suspend fun WebSocketSession.receiveState(): GameState = (receiveEvent() as ServerEvent.StateChanged).state

/** Reads states until one matches, absorbing anything broadcast in between. */
internal suspend fun WebSocketSession.awaitStateWhere(
    timeoutMs: Long = 5_000,
    predicate: (GameState) -> Boolean,
): GameState = withTimeout(timeoutMs) {
    while (true) {
        val state = receiveState()
        if (predicate(state)) return@withTimeout state
    }
    @Suppress("UNREACHABLE_CODE")
    receiveState()
}

internal suspend fun WebSocketSession.receiveHandshake(): Pair<ServerEvent.Joined, GameState> {
    var joined: ServerEvent.Joined? = null
    var state: GameState? = null
    withTimeout(5_000) {
        while (joined == null || state == null) {
            when (val event = receiveEvent()) {
                is ServerEvent.Joined -> joined = event
                is ServerEvent.StateChanged -> state = event.state
                else -> {}
            }
        }
    }
    return joined!! to state!!
}

internal suspend fun WebSocketSession.sendReconnect(token: String) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.Reconnect(token))))
}

internal suspend fun WebSocketSession.sendMakeTurn(x: Int, y: Int) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.MakeTurn(x, y))))
}

internal suspend fun WebSocketSession.sendRequestRematch() {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestRematch)))
}

internal suspend fun WebSocketSession.sendRequestDraw() {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestDraw)))
}

internal suspend fun WebSocketSession.sendRequestUndo() {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestUndo)))
}

internal suspend fun WebSocketSession.sendReact(emoji: String) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.React(emoji))))
}

internal suspend fun WebSocketSession.sendSetName(name: String) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.SetName(name))))
}
