package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.GameLobby
import com.m4isper.tictactoe.api.webUi
import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.game.TicTacToeGame
import com.m4isper.tictactoe.plugins.configureMonitoring
import com.m4isper.tictactoe.plugins.configureRouting
import com.m4isper.tictactoe.plugins.configureSerialization
import com.m4isper.tictactoe.plugins.configureSockets
import com.m4isper.tictactoe.stats.JsonScoreStore
import com.m4isper.tictactoe.stats.Scoreboard
import io.ktor.server.application.*
import java.io.File

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

private fun defaultScoreboard(): Scoreboard = Scoreboard(JsonScoreStore(File(System.getenv("SCOREBOARD_FILE") ?: "data/scoreboard.json")))

fun Application.module() {
    module(scoreboard = defaultScoreboard())
}

fun Application.module(
    scoreboard: Scoreboard = defaultScoreboard(),
    newGame: (boardSize: Int, winLength: Int, botLevel: BotLevel?, turnSeconds: Int) -> TicTacToeGame =
        { boardSize, winLength, botLevel, turnSeconds ->
            TicTacToeGame(
                boardSize = boardSize,
                winLength = winLength,
                botLevel = botLevel,
                turnTimeoutMs = turnSeconds * 1000L,
                onRoundOver = scoreboard::recordRound,
            )
        },
    reconnectGraceMs: Long = 10_000L,
) {
    val lobby = GameLobby(newGame, reconnectGraceMs)
    monitor.subscribe(ApplicationStopped) { lobby.shutdownAll() }
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting(lobby)
    webUi(scoreboard, lobby)
}
