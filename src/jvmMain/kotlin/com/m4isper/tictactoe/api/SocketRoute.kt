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
private const val MESSAGE_WINDOW_MS = 1_000L
private const val MESSAGES_PER_WINDOW = 20

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
            var botLevel: BotLevel? = null
            var secondBotLevel: BotLevel? = null
            when (botParam) {
                null -> {}

                "1" -> botLevel = BotLevel.NORMAL

                // `bot=1` stays a synonym of `normal` for older links
                "2" -> {
                    botLevel = BotLevel.NORMAL // `bot=2`: an exhibition — both slots play
                    secondBotLevel = BotLevel.NORMAL
                }

                "easy" -> botLevel = BotLevel.EASY

                "normal" -> botLevel = BotLevel.NORMAL

                "hard" -> botLevel = BotLevel.HARD

                else -> {
                    sendError(ErrorReason.INVALID_BOT_LEVEL)
                    return@webSocket
                }
            }

            val turnParam = call.request.queryParameters["turn"]
            val turnSeconds = turnParam?.toIntOrNull()
            if (turnParam != null && turnSeconds !in GameRules.MIN_TURN_SECONDS..GameRules.MAX_TURN_SECONDS) {
                sendError(ErrorReason.INVALID_TURN_TIME)
                return@webSocket
            }
            val turn = turnSeconds ?: GameRules.DEFAULT_TURN_SECONDS

            val clockParam = call.request.queryParameters["clock"]
            val clockSeconds = clockParam?.toIntOrNull()
            if (clockParam != null && clockSeconds !in GameRules.MIN_CLOCK_SECONDS..GameRules.MAX_CLOCK_SECONDS) {
                sendError(ErrorReason.INVALID_CLOCK)
                return@webSocket
            }
            val clock = clockSeconds ?: 0

            var binding = lobby.join(this, room, size, winLength ?: size, botLevel, secondBotLevel, turn, clock) ?: run {
                sendError(ErrorReason.ROOM_IS_FULL)
                return@webSocket
            }
            try {
                send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Joined(binding.token, binding.symbol)))
                if (binding.symbol == null) {
                    // spectators get no join broadcast of their own — hand them the current state
                    send(protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(binding.game.currentState())))
                }

                var windowStart = System.currentTimeMillis()
                var messagesInWindow = 0
                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach
                    // flood control: excess frames within the window are dropped quietly
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= MESSAGE_WINDOW_MS) {
                        windowStart = now
                        messagesInWindow = 0
                    }
                    messagesInWindow++
                    if (messagesInWindow > MESSAGES_PER_WINDOW) return@consumeEach

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

                        is ClientMessage.RequestDraw -> when (val symbol = binding.symbol) {
                            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
                            else -> binding.game.requestDraw(symbol, this)?.let(ServerEvent::Error)
                        }

                        is ClientMessage.RequestUndo -> when (val symbol = binding.symbol) {
                            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
                            else -> binding.game.requestUndo(symbol, this)?.let(ServerEvent::Error)
                        }

                        is ClientMessage.React -> {
                            val rejection = binding.game.react(binding.symbol, this, message.emoji)
                            rejection?.let(ServerEvent::Error) // otherwise the reaction itself went out
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
