package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.TicTacToeGame
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RoomInfo(
    val name: String,
    val players: Int,
    val spectators: Int,
    val boardSize: Int,
    val winLength: Int,
    val botLevel: BotLevel? = null,
    val turnSeconds: Int,
)

@Serializable
data class RoomsResponse(val rooms: List<RoomInfo> = emptyList())

class GameLobby(
    private val newGame: (boardSize: Int, winLength: Int, botLevel: BotLevel?, secondBotLevel: BotLevel?, turnSeconds: Int, clockSeconds: Int) -> TicTacToeGame =
        { boardSize, winLength, botLevel, secondBotLevel, turnSeconds, clockSeconds ->
            TicTacToeGame(
                boardSize = boardSize,
                winLength = winLength,
                botLevel = botLevel,
                secondBotLevel = secondBotLevel,
                turnTimeoutMs = turnSeconds * 1000L,
                clockBudgetMs = clockSeconds.takeIf { it > 0 }?.let { it * 1000L },
            )
        },
    private val reconnectGraceMs: Long = 10_000L,
    private val maxGames: Int = 100,
    private val maxSpectatorsPerGame: Int = 20,
) {
    data class Joined(val game: TicTacToeGame, val symbol: PlayerSymbol?, val token: String)

    private data class TokenBinding(
        val game: TicTacToeGame,
        val symbol: PlayerSymbol?,
        val session: WebSocketSession,
    )

    private val lock = Any()
    private val games = mutableListOf<TicTacToeGame>()
    private val rooms = mutableMapOf<String, TicTacToeGame>()
    private val tokens = mutableMapOf<String, TokenBinding>()
    private val graceJobs = mutableMapOf<String, Job>()
    private val lobbyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A claimed name belongs to one reconnect token, so a second session cannot
    // play (and change the Elo rating) under somebody else's name.
    private val nameOwners = mutableMapOf<String, String>() // lowercased name -> token
    private val tokenNames = mutableMapOf<String, String>() // token -> lowercased name

    fun join(
        session: WebSocketSession,
        room: String? = null,
        boardSize: Int = GameRules.DEFAULT_BOARD_SIZE,
        winLength: Int = boardSize,
        botLevel: BotLevel? = null,
        secondBotLevel: BotLevel? = null,
        turnSeconds: Int = GameRules.DEFAULT_TURN_SECONDS,
        clockSeconds: Int = 0,
    ): Joined? = synchronized(lock) {
        val game = findOrCreateGame(room, boardSize, winLength, botLevel, secondBotLevel, turnSeconds, clockSeconds)
            ?: return null
        val symbol = game.connectPlayer(session)
        if (symbol == null && (room == null || game.spectatorCount() >= maxSpectatorsPerGame)) return null
        val token = UUID.randomUUID().toString()
        tokens[token] = TokenBinding(game, symbol, session)
        if (symbol == null) game.attachSpectator(session)
        Joined(game, symbol, token)
    }

    /** Null when a new game would push the lobby past its cap. Caller holds [lock]. */
    private fun findOrCreateGame(
        room: String?,
        boardSize: Int,
        winLength: Int,
        botLevel: BotLevel?,
        secondBotLevel: BotLevel?,
        turnSeconds: Int,
        clockSeconds: Int,
    ): TicTacToeGame? {
        if (room == null) {
            // pair anonymous joiners only with identical settings, or their size/win/bot would be silently ignored
            games.firstOrNull { !it.isFull() && it.matches(boardSize, winLength, botLevel, turnSeconds, clockSeconds) }
                ?.let { return it }
        } else {
            rooms[room]?.let { return it }
        }
        if (games.size + rooms.size >= maxGames) return null
        val game = newGame(boardSize, winLength, botLevel, secondBotLevel, turnSeconds, clockSeconds)
        if (room == null) games.add(game) else rooms[room] = game
        return game
    }

    /** A name stays with the token that claimed it; another live session cannot take it. */
    fun claimName(token: String, name: String): Boolean = synchronized(lock) {
        val key = name.lowercase()
        val owner = nameOwners[key]
        if (owner != null && owner != token) return false
        tokenNames[token]?.let { previous ->
            if (previous != key) nameOwners.remove(previous)
        }
        nameOwners[key] = token
        tokenNames[token] = key
        true
    }

    fun releaseName(token: String): Unit = synchronized(lock) {
        tokenNames.remove(token)?.let { nameOwners.remove(it) }
    }

    fun sessionClosed(token: String, session: WebSocketSession): Unit = synchronized(lock) {
        val binding = tokens[token] ?: return
        if (binding.symbol == null) {
            tokens.remove(token)
            releaseNameLocked(token)
            binding.game.detachSpectator(session)
            return
        }
        if (!binding.game.detachSession(binding.symbol, session)) return
        graceJobs[token] = lobbyScope.launch {
            delay(reconnectGraceMs)
            synchronized(lock) {
                val pending = tokens.remove(token) ?: return@launch
                val symbol = pending.symbol ?: return@launch
                releaseNameLocked(token)
                pending.game.disconnectPlayer(symbol)
                if (pending.game.isEmpty()) retire(pending.game)
            }
        }
    }

    fun reconnect(token: String, session: WebSocketSession): Joined? = synchronized(lock) {
        val binding = tokens[token] ?: return null
        graceJobs.remove(token)?.cancel()
        if (binding.symbol == null) {
            binding.game.attachSpectator(session)
        } else {
            binding.game.reattachSession(binding.symbol, session)
        }
        Joined(binding.game, binding.symbol, token)
    }

    fun abandonJoin(token: String): Unit = synchronized(lock) {
        val binding = tokens.remove(token) ?: return
        graceJobs.remove(token)?.cancel()
        releaseNameLocked(token)
        if (binding.symbol == null) {
            binding.game.detachSpectator(binding.session)
            return
        }
        binding.game.disconnectPlayer(binding.symbol)
        if (binding.game.isEmpty()) retire(binding.game)
    }

    fun shutdownAll(): Unit = synchronized(lock) {
        graceJobs.values.forEach { it.cancel() }
        graceJobs.clear()
        (games + rooms.values).forEach { it.shutdown() }
        games.clear()
        rooms.clear()
        tokens.clear()
        nameOwners.clear()
        tokenNames.clear()
        lobbyScope.cancel()
    }

    /** Caller already holds [lock]. */
    private fun releaseNameLocked(token: String) {
        tokenNames.remove(token)?.let { nameOwners.remove(it) }
    }

    /** Every named room that still holds players — the page's room list. */
    fun roomSnapshots(): List<RoomInfo> = synchronized(lock) {
        rooms.mapNotNull { (name, game) ->
            val players = game.currentState().connectionPlayers.size
            if (players == 0) return@mapNotNull null // an emptied room is on its way out
            RoomInfo(
                name = name,
                players = players,
                spectators = game.spectatorCount(),
                boardSize = game.boardSize,
                winLength = game.winLength,
                botLevel = game.botLevel,
                turnSeconds = (game.turnTimeoutMs / 1000L).toInt(),
            )
        }.sortedBy { it.name }
    }

    private fun retire(game: TicTacToeGame): Unit = synchronized(lock) {
        games.remove(game)
        rooms.values.remove(game)
        game.shutdown()
    }
}
