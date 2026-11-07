package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.ServerLimits
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.protocol.ServerEvent
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.*
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerGuardsTest {

    @Test
    fun `a foreign browser origin is refused at the handshake`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket(
            "/play?room=origin",
            request = { header(HttpHeaders.Origin, "https://evil.example") },
        ) {
            val reason = withTimeoutOrNull(2_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun `an explicitly allowed origin joins normally`() = testApplication {
        application {
            testModule(limits = ServerLimits(allowedOrigins = listOf("https://play.example")))
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket(
            "/play?room=origin2",
            request = { header(HttpHeaders.Origin, "https://play.example") },
        ) {
            val (joined, _) = receiveHandshake()
            assertNotNull(joined.symbol)
        }
    }

    @Test
    fun `ready reports live connections and releases them on close`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        suspend fun awaitConnections(count: Int) {
            withTimeoutOrNull(2_000) {
                while ("\"connections\":$count" !in client.get("/ready").bodyAsText()) delay(20)
                true
            } ?: error("ready never reported $count connections")
        }

        awaitConnections(0)
        client.webSocket("/play?room=ready") {
            receiveHandshake()
            awaitConnections(1)
        }
        awaitConnections(0)
    }

    @Test
    fun `the per address cap refuses an extra socket`() = testApplication {
        application {
            testModule(limits = ServerLimits(maxConnectionsPerIp = 1))
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=cap") {
            receiveHandshake()
            client.webSocket("/play?room=cap2") {
                assertEquals(ServerEvent.Error(ErrorReason.CONNECTION_LIMIT), receiveEvent())
            }
        }
    }
}
