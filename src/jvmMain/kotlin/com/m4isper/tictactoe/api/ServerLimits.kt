package com.m4isper.tictactoe.api

import kotlinx.serialization.Serializable

/** Runtime bounds, read once from the environment so a deployment needs no rebuild. */
data class ServerLimits(
    val maxGames: Int = 100,
    val maxSpectatorsPerGame: Int = 20,
    val maxConnectionsPerIp: Int = 8,
    val maxConnections: Int = 400,
    val allowedOrigins: List<String> = emptyList(),
) {
    companion object {
        private val originPattern = Regex("https?://[A-Za-z0-9._:-]+")

        fun fromEnvironment(read: (String) -> String? = System::getenv): ServerLimits = ServerLimits(
            maxGames = read.int("MAX_GAMES", 100, 1..10_000),
            maxSpectatorsPerGame = read.int("MAX_SPECTATORS", 20, 0..1_000),
            maxConnectionsPerIp = read.int("MAX_CONNECTIONS_PER_IP", 8, 1..1_000),
            maxConnections = read.int("MAX_CONNECTIONS", 400, 1..100_000),
            allowedOrigins = read("ALLOWED_ORIGINS")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it == "*" || originPattern.matches(it) }
                .orEmpty(),
        )

        private fun ((String) -> String?).int(name: String, default: Int, range: IntRange): Int = this(name)?.trim()?.toIntOrNull()?.coerceIn(range) ?: default
    }
}

/** A readiness snapshot: enough to see the lobby is alive and not leaking slots. */
@Serializable
data class HealthResponse(
    val status: String = "ok",
    val games: Int = 0,
    val pendingGrace: Int = 0,
    val connections: Int = 0,
)
