package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.ClientMessage
import com.m4isper.tictactoe.api.ServerEvent
import com.m4isper.tictactoe.api.protocolJson
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.m4isper.tictactoe.SocketRoute")

fun Route.socket(lobby: GameLobby) {
    route("/play") {
        webSocket {
            var joined = lobby.join(this)
            try {
                send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Joined(joined.token, joined.symbol)))

                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach
                    val message = runCatching { protocolJson.decodeFromString<ClientMessage>(frame.readText()) }
                        .getOrNull()

                    val event: ServerEvent? = when (message) {
                        is ClientMessage.MakeTurn -> joined.game.finishTurn(joined.symbol, message.x, message.y)
                            ?.let(ServerEvent::Error)

                        is ClientMessage.Reconnect -> {
                            val rebound = lobby.reconnect(message.token, this)
                            if (rebound == null) {
                                ServerEvent.Error("unknown token")
                            } else {
                                lobby.abandonJoin(joined.token)
                                joined = rebound
                                ServerEvent.StateChanged(rebound.game.currentState())
                            }
                        }

                        null -> ServerEvent.Error("malformed message")
                    }
                    if (event != null) send(protocolJson.encodeToString<ServerEvent>(event))
                }
            } catch (e: Exception) {
                logger.error("WebSocket session failed for player {}", joined.symbol, e)
            } finally {
                lobby.sessionClosed(joined.token, this)
            }
        }
    }
}
