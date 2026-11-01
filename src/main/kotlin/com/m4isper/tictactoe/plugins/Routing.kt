package com.m4isper.tictactoe.plugins

import com.m4isper.tictactoe.models.TicTacToeGame
import com.m4isper.tictactoe.socket
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(game: TicTacToeGame) {
    routing {
        socket(game)
    }
}
