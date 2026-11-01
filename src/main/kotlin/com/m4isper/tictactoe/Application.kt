package com.m4isper.tictactoe

import com.m4isper.tictactoe.models.TicTacToeGame
import com.m4isper.tictactoe.plugins.configureMonitoring
import com.m4isper.tictactoe.plugins.configureRouting
import com.m4isper.tictactoe.plugins.configureSerialization
import com.m4isper.tictactoe.plugins.configureSockets
import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val game = TicTacToeGame()
    environment.monitor.subscribe(ApplicationStopped) { game.shutdown() }
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureRouting(game)
}
