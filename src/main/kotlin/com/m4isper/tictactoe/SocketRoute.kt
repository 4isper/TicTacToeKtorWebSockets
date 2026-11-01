package com.m4isper.tictactoe

import com.m4isper.tictactoe.models.MakeTurn
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.m4isper.tictactoe.SocketRoute")

fun Route.socket(lobby: GameLobby) {
    route("/play") {
        webSocket {
            val (game, player) = lobby.join(this)

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val action = extractAction(frame.readText())
                        game.finishTurn(player, action.x, action.y)
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

private fun extractAction(message: String): MakeTurn {
    val type = message.substringBefore("#")
    val body = message.substringAfter("#")
    return if (type == "make_turn") {
        Json.decodeFromString(body)
    } else {
        MakeTurn(-1, -1)
    }
}
