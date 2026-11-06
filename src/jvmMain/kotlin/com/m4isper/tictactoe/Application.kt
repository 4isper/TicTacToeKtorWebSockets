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
import com.m4isper.tictactoe.stats.SqliteScoreStore
import io.ktor.server.application.*
import java.io.File

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

private fun defaultScoreboard(): Scoreboard {
    val file = File(System.getenv("SCOREBOARD_FILE") ?: "data/scoreboard.json")
    val store = if (file.extension == "db" || file.extension == "sqlite") SqliteScoreStore(file) else JsonScoreStore(file)
    return Scoreboard(store)
}

fun Application.module() {
    module(scoreboard = defaultScoreboard())
}

fun Application.module(
    scoreboard: Scoreboard = defaultScoreboard(),
    newGame: (
        boardSize: Int,
        winLength: Int,
        botLevel: BotLevel?,
        secondBotLevel: BotLevel?,
        turnSeconds: Int,
        clockSeconds: Int,
        seriesTarget: Int,
    ) -> TicTacToeGame =
        { boardSize, winLength, botLevel, secondBotLevel, turnSeconds, clockSeconds, seriesTarget ->
            TicTacToeGame(
                boardSize = boardSize,
                winLength = winLength,
                botLevel = botLevel,
                secondBotLevel = secondBotLevel,
                turnTimeoutMs = turnSeconds * 1000L,
                clockBudgetMs = clockSeconds.takeIf { it > 0 }?.let { it * 1000L },
                seriesTarget = seriesTarget.takeIf { it > 0 },
                onRoundOver = scoreboard::recordRound,
            )
        },
    reconnectGraceMs: Long = 10_000L,
) {
    val lobby = GameLobby(newGame, reconnectGraceMs, ratingOf = scoreboard::ratingOf)
    monitor.subscribe(ApplicationStopped) { lobby.shutdownAll() }
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting(lobby, scoreboard::ratingOf)
    webUi(scoreboard, lobby)
}
