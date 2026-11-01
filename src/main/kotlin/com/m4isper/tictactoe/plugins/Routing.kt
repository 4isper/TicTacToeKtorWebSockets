package com.m4isper.tictactoe.plugins

import com.m4isper.tictactoe.GameLobby
import com.m4isper.tictactoe.socket
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(lobby: GameLobby) {
    routing {
        socket(lobby)
    }
}
