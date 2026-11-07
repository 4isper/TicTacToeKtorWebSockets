package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.normalizeChatText
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.isOriginAllowed
import com.m4isper.tictactoe.protocol.protocolJson
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.m4isper.tictactoe.api.SocketRoute")

private val roomNamePattern = Regex("[A-Za-z0-9_-]{1,32}")
private val playerKeyPattern = Regex("[A-Za-z0-9_-]{1,64}")
private const val MAX_NAME_LENGTH = 20
private const val MESSAGE_WINDOW_MS = 1_000L
private const val MESSAGES_PER_WINDOW = 20

private data class BotLevels(val first: BotLevel?, val second: BotLevel?)

private data class BoardSettings(val size: Int, val win: Int)

private data class TimeSettings(val turn: Int, val clock: Int)

fun Route.socket(
    lobby: GameLobby,
    ratingOf: (String) -> Int?,
    connections: ConnectionLimiter = ConnectionLimiter(8, 400),
    allowedOrigins: List<String> = emptyList(),
) {
    route("/play") {
        webSocket {
            val headers = call.request.headers
            val host = headers[HttpHeaders.XForwardedHost] ?: headers[HttpHeaders.Host] ?: call.request.local.serverHost
            val scheme = headers[HttpHeaders.XForwardedProto] ?: call.request.local.scheme
            if (!isOriginAllowed(headers[HttpHeaders.Origin], host, scheme, allowedOrigins)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
                return@webSocket
            }
            val ip = call.request.local.remoteHost
            if (!connections.acquire(ip)) {
                sendError(ErrorReason.CONNECTION_LIMIT)
                return@webSocket
            }
            try {
                serveSession(lobby, ratingOf)
            } finally {
                connections.release(ip)
            }
        }
    }
}

/** Everything after the handshake guards: join, greet, dispatch frames, release the lobby slot. */
private suspend fun DefaultWebSocketServerSession.serveSession(
    lobby: GameLobby,
    ratingOf: (String) -> Int?,
) {
    val settings = parseSettings(ratingOf) ?: return
    val joined = lobby.join(this, settings) ?: run {
        sendError(ErrorReason.ROOM_IS_FULL)
        return
    }
    val handler = SessionHandler(this, lobby, joined, settings.playerKey)
    try {
        send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Joined(joined.token, joined.symbol)))
        if (joined.symbol != null && settings.name != null) {
            // a name carried by the join URL claims itself like set_name would; a refusal just stays unnamed
            if (!lobby.claimName(joined.token, settings.name, settings.playerKey)) {
                sendError(ErrorReason.NAME_TAKEN)
            } else {
                joined.game.setName(joined.symbol, settings.name)?.let { sendError(it) }
            }
        }
        if (joined.symbol == null) {
            // spectators get no join broadcast of their own — hand them the current state
            send(protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(joined.game.currentState())))
        }

        val limiter = MessageRateLimiter()
        incoming.consumeEach { frame ->
            if (frame !is Frame.Text) return@consumeEach
            if (!limiter.allow()) return@consumeEach
            val message = runCatching { protocolJson.decodeFromString<ClientMessage>(frame.readText()) }
                .getOrNull()
            handler.handle(message)?.let { send(protocolJson.encodeToString<ServerEvent>(it)) }
        }
    } catch (e: Exception) {
        logger.error("WebSocket session failed for player {}", handler.binding.symbol, e)
    } finally {
        lobby.sessionClosed(handler.binding.token, this)
    }
}

/** One session's message dispatch; the binding changes when the client reconnects. */
private class SessionHandler(
    private val session: DefaultWebSocketServerSession,
    private val lobby: GameLobby,
    initial: GameLobby.Joined,
    private val playerKey: String?,
) {
    var binding: GameLobby.Joined = initial
        private set

    suspend fun handle(message: ClientMessage?): ServerEvent? = when (message) {
        is ClientMessage.MakeTurn -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
            else -> binding.game.finishTurn(symbol, session, message.x, message.y)?.let(ServerEvent::Error)
        }

        is ClientMessage.RequestRematch -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
            else -> binding.game.requestRematch(symbol, session)?.let(ServerEvent::Error)
        }

        is ClientMessage.RequestDraw -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
            else -> binding.game.requestDraw(symbol, session)?.let(ServerEvent::Error)
        }

        is ClientMessage.RequestResign -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
            else -> binding.game.requestResign(symbol, session)?.let(ServerEvent::Error)
        }

        is ClientMessage.RequestUndo -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)
            else -> binding.game.requestUndo(symbol, session)?.let(ServerEvent::Error)
        }

        is ClientMessage.React -> {
            val rejection = binding.game.react(binding.symbol, session, message.emoji)
            rejection?.let(ServerEvent::Error) // otherwise the reaction itself went out
        }

        is ClientMessage.Chat -> handleChat(message.text, session)

        is ClientMessage.SetName -> when (val symbol = binding.symbol) {
            null -> ServerEvent.Error(ErrorReason.NOT_YOUR_TURN)

            else -> {
                val name = message.name.trim()
                when {
                    name.isEmpty() || name.length > MAX_NAME_LENGTH -> ServerEvent.Error(ErrorReason.INVALID_NAME)
                    !lobby.claimName(binding.token, name, playerKey) -> ServerEvent.Error(ErrorReason.NAME_TAKEN)
                    else -> binding.game.setName(symbol, name)?.let(ServerEvent::Error)
                }
            }
        }

        is ClientMessage.Reconnect -> {
            val rebound = lobby.reconnect(message.token, session)
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

    /** Validates the line at the wire boundary, then lets the room broadcast it. */
    private suspend fun handleChat(rawText: String, session: DefaultWebSocketServerSession): ServerEvent? {
        val text = normalizeChatText(rawText) ?: return ServerEvent.Error(ErrorReason.INVALID_CHAT)
        val name = binding.symbol?.let { binding.game.currentState().playerNames[it] }
        binding.game.chat(binding.symbol, name, session, text)
        return null // the chat line itself went out to the room
    }
}

/** Excess frames within a window are dropped quietly. */
private class MessageRateLimiter {
    private var windowStart = System.currentTimeMillis()
    private var messagesInWindow = 0

    fun allow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart >= MESSAGE_WINDOW_MS) {
            windowStart = now
            messagesInWindow = 0
        }
        messagesInWindow++
        return messagesInWindow <= MESSAGES_PER_WINDOW
    }
}

/** Every invalid parameter answers with its own error; the caller then leaves the session. */
private suspend fun DefaultWebSocketServerSession.parseSettings(ratingOf: (String) -> Int?): JoinSettings? {
    val params = call.request.queryParameters
    val room = params["room"]
    if (room != null && !roomNamePattern.matches(room)) {
        sendError(ErrorReason.INVALID_ROOM_NAME)
        return null
    }
    val board = boardSettings() ?: return null
    val bots = botLevels() ?: return null
    val time = timeSettings() ?: return null
    val seriesTarget = intParam("wins", 1..GameRules.MAX_SERIES_WINS, 0, ErrorReason.INVALID_WINS) ?: return null

    // an invalid name answers its error but keeps the session: the client would only retry with the same name
    val rawName = params["name"]?.trim()?.takeIf { it.isNotEmpty() }
    if (rawName != null && rawName.length > MAX_NAME_LENGTH) sendError(ErrorReason.INVALID_NAME)
    val name = rawName?.takeIf { it.length <= MAX_NAME_LENGTH }
    // a malformed key is ignored: the session stays keyless and its name claim is ephemeral
    val playerKey = params["key"]?.takeIf { playerKeyPattern.matches(it) }
    return JoinSettings(
        room = room,
        size = board.size,
        winLength = board.win,
        botLevel = bots.first,
        secondBotLevel = bots.second,
        turnSeconds = time.turn,
        clockSeconds = time.clock,
        seriesTarget = seriesTarget,
        spectate = params["spectate"] == "1",
        name = name,
        playerKey = playerKey,
        rating = name?.let(ratingOf),
    )
}

private suspend fun DefaultWebSocketServerSession.boardSettings(): BoardSettings? {
    val size = intParam("size", GameRules.MIN_BOARD_SIZE..GameRules.MAX_BOARD_SIZE, GameRules.DEFAULT_BOARD_SIZE, ErrorReason.INVALID_BOARD_SIZE) ?: return null
    val win = intParam("win", GameRules.MIN_WIN_LENGTH..size, size, ErrorReason.INVALID_WIN_LENGTH) ?: return null
    return BoardSettings(size, win)
}

private suspend fun DefaultWebSocketServerSession.timeSettings(): TimeSettings? {
    val turn = intParam("turn", GameRules.MIN_TURN_SECONDS..GameRules.MAX_TURN_SECONDS, GameRules.DEFAULT_TURN_SECONDS, ErrorReason.INVALID_TURN_TIME) ?: return null
    val clock = intParam("clock", GameRules.MIN_CLOCK_SECONDS..GameRules.MAX_CLOCK_SECONDS, 0, ErrorReason.INVALID_CLOCK) ?: return null
    return TimeSettings(turn, clock)
}

/** A missing parameter takes the default; a present one must be an integer inside the range. */
private suspend fun DefaultWebSocketServerSession.intParam(
    name: String,
    range: IntRange,
    default: Int,
    error: ErrorReason,
): Int? {
    val raw = call.request.queryParameters[name] ?: return default
    val value = raw.toIntOrNull()
    if (value == null || value !in range) {
        sendError(error)
        return null
    }
    return value
}

private suspend fun DefaultWebSocketServerSession.botLevels(): BotLevels? = when (call.request.queryParameters["bot"]) {
    null -> BotLevels(null, null)

    "1", "normal" -> BotLevels(BotLevel.NORMAL, null)

    // `bot=1` stays a synonym of `normal`
    "2" -> BotLevels(BotLevel.NORMAL, BotLevel.NORMAL)

    // an exhibition — both slots play
    "easy" -> BotLevels(BotLevel.EASY, null)

    "hard" -> BotLevels(BotLevel.HARD, null)

    else -> {
        sendError(ErrorReason.INVALID_BOT_LEVEL)
        null
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(reason: ErrorReason) {
    send(protocolJson.encodeToString<ServerEvent>(ServerEvent.Error(reason)))
}
