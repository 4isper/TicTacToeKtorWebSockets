package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.GameLobby
import com.m4isper.tictactoe.api.webUi
import com.m4isper.tictactoe.game.TicTacToeGame
import com.m4isper.tictactoe.plugins.configureMonitoring
import com.m4isper.tictactoe.plugins.configureRouting
import com.m4isper.tictactoe.plugins.configureSerialization
import com.m4isper.tictactoe.plugins.configureSockets
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    module(newGame = { boardSize, winLength, botEnabled ->
        TicTacToeGame(boardSize = boardSize, winLength = winLength, botEnabled = botEnabled)
    })
}

fun Application.module(
    newGame: (boardSize: Int, winLength: Int, botEnabled: Boolean) -> TicTacToeGame = { boardSize, winLength, botEnabled ->
        TicTacToeGame(boardSize = boardSize, winLength = winLength, botEnabled = botEnabled)
    },
    reconnectGraceMs: Long = 10_000L,
) {
    val lobby = GameLobby(newGame, reconnectGraceMs)
    monitor.subscribe(ApplicationStopped) { lobby.shutdownAll() }
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting(lobby)
    webUi()
}
