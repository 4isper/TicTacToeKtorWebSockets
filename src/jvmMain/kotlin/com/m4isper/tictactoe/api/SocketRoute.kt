package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.m4isper.tictactoe.api.SocketRoute")

private val roomNamePattern = Regex("[A-Za-z0-9_-]{1,32}")
private const val MAX_NAME_LENGTH = 20

fun Route.socket(lobby: GameLobby) {
    route("/play") {
        webSocket {
            val roomParam = call.request.queryParameters["room"]
            val room = roomParam?.takeIf { roomNamePattern.matches(it) }
            if (roomParam != null && room == null) {
                sendError(ErrorReason.INVALID_ROOM_NAME)
                return@webSocket
            }

            val sizeParam = call.request.queryParameters["size"]
            val boardSize = sizeParam?.toIntOrNull()
            if (sizeParam != null && boardSize !in GameRules.MIN_BOARD_SIZE..GameRules.MAX_BOARD_SIZE) {
                sendError(ErrorReason.INVALID_BOARD_SIZE)
                return@webSocket
            }
            val size = boardSize ?: GameRules.DEFAULT_BOARD_SIZE

            val winParam = call.request.queryParameters["win"]
            val winLength = winParam?.toIntOrNull()
            if (winParam != null && (winLength == null || winLength !in GameRules.MIN_WIN_LENGTH..size)) {
                sendError(ErrorReason.INVALID_WIN_LENGTH)
                return@webSocket
            }

            val botParam = call.request.queryParameters["bot"]
            val botLevel = botParam?.let { parseBotLevel(it) }
            if (botParam != null && botLevel == null) {
                sendError(ErrorReason.INVALID_BOT_LEVEL)
                return@webSocket
            }

            val turnParam = call.request.queryParameters["turn"]
            val turnSeconds = turnParam?.toIntOrNull()
            if (turnParam != null && turnSeconds !in GameRules.MIN_TURN_SECONDS..GameRules.MAX_TURN_SECONDS) {
                sendError(ErrorReason.INVALID_TURN_TIME)
                return@webSocket
            }
            val turn = turnSeconds ?: GameRules.DEFAULT_TURN_SECONDS

            var binding = lobby.join(this, room, size, winLength ?: size, botLevel, turn) ?: run {
                sendError(ErrorReason.ROOM_IS_FULL)
                return@webSocket
            }
            try {
                send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Joined(binding.token, binding.symbol)))
                if (binding.symbol == null) {
                    // spectators get no join broadcast of their own — hand them the current state
                    send(protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(binding.game.currentState())))
                }

                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach
                    val message = runCatching { protocolJson.decodeFromString<ClientMessage>(frame.readText()) }
                        .getOrNull()

                    val event: ServerEvent? = when (message) {
                        is ClientMessage.MakeTurn -> when (val symbol = binding.symbol) {
                            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)

                            else -> binding.game.finishTurn(symbol, this, message.x, message.y)
                                ?.let(ServerEvent::Error)
                        }

                        is ClientMessage.RequestRematch -> when (val symbol = binding.symbol) {
                            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
                            else -> binding.game.requestRematch(symbol, this)?.let(ServerEvent::Error)
                        }

                        is ClientMessage.SetName -> when (val symbol = binding.symbol) {
                            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)

                            else -> {
                                val name = message.name.trim()
                                if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                                    ServerEvent.Error(ErrorReason.INVALID_NAME)
                                } else {
                                    // a null result means accepted — the broadcast carries the new state
                                    binding.game.setName(symbol, name)?.let(ServerEvent::Error)
                                }
                            }
                        }

                        is ClientMessage.Reconnect -> {
                            val rebound = lobby.reconnect(message.token, this)
                            if (rebound == null) {
                                ServerEvent.Error(ErrorReason.UNKNOWN_TOKEN)
                            } else {
                                lobby.abandonJoin(binding.token)
                                binding = rebound
                                ServerEvent.StateChanged(rebound.game.currentState())
                            }
                        }

                        null -> ServerEvent.Error(ErrorReason.MALFORMED_MESSAGE)
                    }
                    if (event != null) send(protocolJson.encodeToString<ServerEvent>(event))
                }
            } catch (e: Exception) {
                logger.error("WebSocket session failed for player {}", binding.symbol, e)
            } finally {
                lobby.sessionClosed(binding.token, this)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(reason: ErrorReason) {
    send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Error(reason)))
}

/** `bot=1` stays a synonym of `normal` for older links. */
private fun parseBotLevel(raw: String): BotLevel? = when (raw) {
    "1", "normal" -> BotLevel.NORMAL
    "easy" -> BotLevel.EASY
    "hard" -> BotLevel.HARD
    else -> null
}
