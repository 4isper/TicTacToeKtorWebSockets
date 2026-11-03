package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.TicTacToeGame
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.UUID

class GameLobby(
    private val newGame: (boardSize: Int) -> TicTacToeGame = { TicTacToeGame(boardSize = it) },
    private val reconnectGraceMs: Long = 10_000L,
) {
    data class Joined(val game: TicTacToeGame, val symbol: PlayerSymbol, val token: String)

    private data class TokenBinding(val game: TicTacToeGame, val symbol: PlayerSymbol)

    private val lock = Any()
    private val games = mutableListOf<TicTacToeGame>()
    private val rooms = mutableMapOf<String, TicTacToeGame>()
    private val tokens = mutableMapOf<String, TokenBinding>()
    private val graceJobs = mutableMapOf<String, Job>()
    private val lobbyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun join(
        session: WebSocketSession,
        room: String? = null,
        boardSize: Int = GameRules.DEFAULT_BOARD_SIZE,
    ): Joined? = synchronized(lock) {
        val game = if (room == null) {
            games.firstOrNull { !it.isFull() } ?: newGame(boardSize).also { games.add(it) }
        } else {
            rooms.getOrPut(room) { newGame(boardSize) }
        }
        val symbol = game.connectPlayer(session) ?: return null
        val token = UUID.randomUUID().toString()
        tokens[token] = TokenBinding(game, symbol)
        Joined(game, symbol, token)
    }

    fun sessionClosed(token: String, session: WebSocketSession): Unit = synchronized(lock) {
        val binding = tokens[token] ?: return
        if (!binding.game.detachSession(binding.symbol, session)) return
        graceJobs[token] = lobbyScope.launch {
            delay(reconnectGraceMs)
            synchronized(lock) {
                val pending = tokens.remove(token) ?: return@launch
                pending.game.disconnectPlayer(pending.symbol)
                if (pending.game.isEmpty()) retire(pending.game)
            }
        }
    }

    fun reconnect(token: String, session: WebSocketSession): Joined? = synchronized(lock) {
        val binding = tokens[token] ?: return null
        graceJobs.remove(token)?.cancel()
        binding.game.reattachSession(binding.symbol, session)
        Joined(binding.game, binding.symbol, token)
    }

    fun abandonJoin(token: String): Unit = synchronized(lock) {
        val binding = tokens.remove(token) ?: return
        graceJobs.remove(token)?.cancel()
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
        lobbyScope.cancel()
    }

    private fun retire(game: TicTacToeGame): Unit = synchronized(lock) {
        games.remove(game)
        rooms.values.remove(game)
        game.shutdown()
    }
}
