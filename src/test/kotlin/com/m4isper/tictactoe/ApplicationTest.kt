package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.models.GameState
import com.m4isper.tictactoe.models.TicTacToeGame
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

private suspend fun WebSocketSession.skipBroadcast() {
    incoming.receive()
}

private suspend fun WebSocketSession.receiveGameState(): GameState = Json.decodeFromString<GameState>((incoming.receive() as Frame.Text).readText())

private suspend fun WebSocketSession.playTopRowMoves() {
    send(Frame.Text("make_turn#{\"x\":0,\"y\":0}"))
    skipBroadcast()
    skipBroadcast()
    send(Frame.Text("make_turn#{\"x\":0,\"y\":1}"))
    skipBroadcast()
    skipBroadcast()
    send(Frame.Text("make_turn#{\"x\":0,\"y\":2}"))
}

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
            assertEquals(PlayerSymbol.X, state?.playerAtTurn)
            assertEquals(listOf(PlayerSymbol.X), state?.connectionPlayers)
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
            assertEquals(PlayerSymbol.X, state.field[0][0])
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
                    assertEquals(PlayerSymbol.X, state.field[0][2])
                    assertEquals(PlayerSymbol.O, state.field[0][0])
                    done.complete(Unit)
                    awaitCancellation()
                }
            }
            done.await()
            second.cancel()
        }
    }

    @Test
    fun `winning move is detected immediately`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            withTimeout(5_000) { incoming.receive() }

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    withTimeout(5_000) { incoming.receive() }
                    oReady.complete(Unit)
                    withTimeout(5_000) { incoming.receive() }
                    send(Frame.Text("make_turn#{\"x\":1,\"y\":0}"))
                    withTimeout(5_000) { incoming.receive() }
                    withTimeout(5_000) { incoming.receive() }
                    send(Frame.Text("make_turn#{\"x\":1,\"y\":1}"))
                    withTimeout(5_000) { incoming.receive() }
                    awaitCancellation()
                }
            }
            oReady.await()

            withTimeout(5_000) { incoming.receive() } // broadcast of O's connect

            send(Frame.Text("make_turn#{\"x\":0,\"y\":0}"))
            withTimeout(5_000) { incoming.receive() }
            withTimeout(5_000) { incoming.receive() }
            send(Frame.Text("make_turn#{\"x\":0,\"y\":1}"))
            withTimeout(5_000) { incoming.receive() }
            withTimeout(5_000) { incoming.receive() }
            send(Frame.Text("make_turn#{\"x\":0,\"y\":2}"))

            val frame = withTimeout(5_000) { incoming.receive() }
            val state = Json.decodeFromString<GameState>((frame as Frame.Text).readText())
            assertEquals(PlayerSymbol.X, state.winningPlayer)

            second.cancel()
        }
    }

    @Test
    fun `each new pair of connections gets its own game`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            withTimeout(5_000) { incoming.receive() }

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    withTimeout(5_000) { incoming.receive() }
                    oReady.complete(Unit)
                    awaitCancellation()
                }
            }
            oReady.await()

            client.webSocket("/play") {
                val frame = withTimeout(5_000) { incoming.receive() }
                val state = Json.decodeFromString<GameState>((frame as Frame.Text).readText())
                assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
                assertNull(state.field[0][0])
            }

            second.cancel()
        }
    }

    @Test
    fun `score is reported when a round is won`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play") {
            skipBroadcast()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    skipBroadcast()
                    oReady.complete(Unit)
                    skipBroadcast()
                    send(Frame.Text("make_turn#{\"x\":1,\"y\":0}"))
                    skipBroadcast()
                    skipBroadcast()
                    send(Frame.Text("make_turn#{\"x\":1,\"y\":1}"))
                    skipBroadcast()
                    skipBroadcast()
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast()

            playTopRowMoves()

            val s1 = receiveGameState()
            assertEquals(PlayerSymbol.X, s1.winningPlayer)
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), s1.score)

            second.cancel()
        }
    }
}
