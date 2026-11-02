package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.models.TicTacToeGame
import io.ktor.websocket.*

class GameLobby(private val newGame: () -> TicTacToeGame = ::TicTacToeGame) {
    private val lock = Any()
    private val games = mutableListOf<TicTacToeGame>()

    fun join(session: WebSocketSession): Pair<TicTacToeGame, PlayerSymbol> = synchronized(lock) {
        val game = games.firstOrNull { !it.isFull() } ?: newGame().also { games.add(it) }
        val player = game.connectPlayer(session)
        requireNotNull(player) { "game had a free slot but rejected the connection" }
        game to player
    }

    fun leave(game: TicTacToeGame, player: PlayerSymbol): Unit = synchronized(lock) {
        game.disconnectPlayer(player)
        if (game.isEmpty()) {
            games.remove(game)
            game.shutdown()
        }
    }

    fun shutdownAll(): Unit = synchronized(lock) {
        games.forEach { it.shutdown() }
        games.clear()
    }
}
