package com.m4isper.tictactoe.plugins

import com.m4isper.tictactoe.api.GameLobby
import com.m4isper.tictactoe.api.socket
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(
    lobby: GameLobby,
    ratingOf: (String) -> Int? = { null },
) {
    routing {
        get("/health") {
            call.respondText("ok")
        }
        socket(lobby, ratingOf)
    }
}
