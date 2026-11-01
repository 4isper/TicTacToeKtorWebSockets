package com.m4isper.tictactoe

import com.m4isper.tictactoe.models.GameState
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*

class ApplicationTest {

    @Test
    fun `first player receives game state after connect`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            val frame = withTimeout(5_000) { incoming.receive() }
            val text = (frame as? Frame.Text)?.readText()
            val state = text?.let { Json.decodeFromString<GameState>(it) }
            assertEquals('X', state?.playerAtTurn)
            assertEquals(listOf('X'), state?.connectionPlayers)
        }
    }

    @Test
    fun `non-turn message does not drop the connection`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            withTimeout(5_000) { incoming.receive() }

            send(Frame.Text("unknown_type#{}"))

            send(Frame.Text("make_turn#{\"x\":0,\"y\":0}"))
            val frame = withTimeout(5_000) { incoming.receive() }
            val state = Json.decodeFromString<GameState>((frame as Frame.Text).readText())
            assertEquals('X', state.field[0][0])
        }
    }

    @Test
    fun `occupied cell is not overwritten by the opponent`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            withTimeout(5_000) { incoming.receive() }

            send(Frame.Text("make_turn#{\"x\":2,\"y\":0}"))
            withTimeout(5_000) { incoming.receive() }

            val done = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    withTimeout(5_000) { incoming.receive() }

                    send(Frame.Text("make_turn#{\"x\":2,\"y\":0}"))
                    send(Frame.Text("make_turn#{\"x\":0,\"y\":0}"))

                    val frame = withTimeout(5_000) { incoming.receive() }
                    val state = Json.decodeFromString<GameState>((frame as Frame.Text).readText())
                    assertEquals('X', state.field[0][2])
                    assertEquals('O', state.field[0][0])
                    done.complete(Unit)
                    awaitCancellation()
                }
            }
            done.await()
            second.cancel()
        }
    }

    @Test
    fun `third connection is rejected when both players are connected`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            withTimeout(5_000) { incoming.receive() }

            val secondRegistered = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    withTimeout(5_000) { incoming.receive() }
                    secondRegistered.complete(Unit)
                    awaitCancellation()
                }
            }
            secondRegistered.await()

            client.webSocket("/play") {
                val reason = withTimeout(5_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code)
            }

            second.cancel()
        }
    }
}
