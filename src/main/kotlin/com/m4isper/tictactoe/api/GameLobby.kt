package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.TicTacToeGame
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameLobby(
    private val newGame: () -> TicTacToeGame = ::TicTacToeGame,
    private val reconnectGraceMs: Long = 10_000L,
) {
    data class Joined(val game: TicTacToeGame, val symbol: PlayerSymbol, val token: String)

    private data class TokenBinding(val game: TicTacToeGame, val symbol: PlayerSymbol)

    private val lock = Any()
    private val games = mutableListOf<TicTacToeGame>()
    private val tokens = mutableMapOf<String, TokenBinding>()
    private val graceJobs = mutableMapOf<String, Job>()
    private val lobbyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun join(session: WebSocketSession): Joined = synchronized(lock) {
        val game = games.firstOrNull { !it.isFull() } ?: newGame().also { games.add(it) }
        val symbol = game.connectPlayer(session)
        requireNotNull(symbol) { "game had a free slot but rejected the connection" }
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
                if (pending.game.isEmpty()) {
                    games.remove(pending.game)
                    tokens.entries.removeIf { it.value.game === pending.game }
                    pending.game.shutdown()
                }
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
        if (binding.game.isEmpty()) {
            games.remove(binding.game)
            binding.game.shutdown()
        }
    }

    fun shutdownAll(): Unit = synchronized(lock) {
        graceJobs.values.forEach { it.cancel() }
        graceJobs.clear()
        games.forEach { it.shutdown() }
        games.clear()
        tokens.clear()
        lobbyScope.cancel()
    }
}
