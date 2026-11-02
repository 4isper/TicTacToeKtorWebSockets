package com.m4isper.tictactoe.plugins

import com.m4isper.tictactoe.api.GameLobby
import com.m4isper.tictactoe.api.socket
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting(lobby: GameLobby) {
    routing {
        socket(lobby)
    }
}
