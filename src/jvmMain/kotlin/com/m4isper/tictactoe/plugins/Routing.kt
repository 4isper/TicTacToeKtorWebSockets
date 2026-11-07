package com.m4isper.tictactoe.plugins

import com.m4isper.tictactoe.api.ConnectionLimiter
import com.m4isper.tictactoe.api.GameLobby
import com.m4isper.tictactoe.api.HealthResponse
import com.m4isper.tictactoe.api.ServerLimits
import com.m4isper.tictactoe.api.socket
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val healthJson = Json { encodeDefaults = true }

fun Application.configureRouting(
    lobby: GameLobby,
    ratingOf: (String) -> Int? = { null },
    limits: ServerLimits = ServerLimits(),
) {
    val limiter = ConnectionLimiter(limits.maxConnectionsPerIp, limits.maxConnections)
    routing {
        get("/health") {
            call.respondText("ok") // liveness stays a cheap, stable probe
        }
        get("/ready") {
            call.respondText(
                healthJson.encodeToString(
                    HealthResponse(
                        games = lobby.gameCount(),
                        pendingGrace = lobby.pendingGraceCount(),
                        connections = limiter.total(),
                    ),
                ),
                ContentType.Application.Json,
            )
        }
        socket(lobby, ratingOf, limiter, limits.allowedOrigins)
    }
}
