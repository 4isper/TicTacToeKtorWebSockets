package com.m4isper.tictactoe.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerLimitsTest {

    @Test
    fun `missing or invalid values keep the defaults`() {
        val limits = ServerLimits.fromEnvironment { null }
        assertEquals(100, limits.maxGames)
        assertEquals(20, limits.maxSpectatorsPerGame)
        assertEquals(8, limits.maxConnectionsPerIp)
        assertEquals(400, limits.maxConnections)
        assertEquals(emptyList(), limits.allowedOrigins)
        assertEquals(ServerLimits(), ServerLimits.fromEnvironment { "not-a-number" })
    }

    @Test
    fun `environment values are parsed and clamped`() {
        val env = mapOf(
            "MAX_GAMES" to "7",
            "MAX_SPECTATORS" to "3",
            "MAX_CONNECTIONS_PER_IP" to "999999",
            "MAX_CONNECTIONS" to "50",
            "ALLOWED_ORIGINS" to "https://play.example, https://two.example, ",
        )
        val limits = ServerLimits.fromEnvironment { env[it] }

        assertEquals(7, limits.maxGames)
        assertEquals(3, limits.maxSpectatorsPerGame)
        assertEquals(1_000, limits.maxConnectionsPerIp) // clamped to the sane ceiling
        assertEquals(50, limits.maxConnections)
        assertEquals(listOf("https://play.example", "https://two.example"), limits.allowedOrigins)
    }
}
