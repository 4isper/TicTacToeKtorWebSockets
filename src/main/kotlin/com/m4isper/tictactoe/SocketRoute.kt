package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.ClientMessage
import com.m4isper.tictactoe.api.ServerEvent
import com.m4isper.tictactoe.api.protocolJson
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.models.TicTacToeGame
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
            val (game, player) = lobby.join(this)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        handleClientMessage(game, player, frame.readText())?.let { event ->
                            send(protocolJson.encodeToString<ServerEvent>(event))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("WebSocket session failed for player {}", player, e)
            } finally {
                lobby.leave(game, player)
            }
        }
    }
}

private fun handleClientMessage(game: TicTacToeGame, player: PlayerSymbol, text: String): ServerEvent? {
    val message = runCatching { protocolJson.decodeFromString<ClientMessage>(text) }.getOrNull()
    return when (message) {
        is ClientMessage.MakeTurn -> game.finishTurn(player, message.x, message.y)
            ?.let(ServerEvent::MoveRejected)

        null -> ServerEvent.MoveRejected("malformed message")
    }
}
