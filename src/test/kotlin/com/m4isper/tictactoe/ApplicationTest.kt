package com.m4isper.tictactoe

import com.m4isper.tictactoe.api.ClientMessage
import com.m4isper.tictactoe.api.ServerEvent
import com.m4isper.tictactoe.api.protocolJson
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.models.GameState
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
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
            val state = receiveState()
            assertEquals(PlayerSymbol.X, state.playerAtTurn)
            assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
        }
    }

    @Test
    fun `malformed message is rejected without dropping the connection`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            skipBroadcast()

            send(Frame.Text("not json at all"))
            assertTrue(receiveEvent() is ServerEvent.MoveRejected)

            send(Frame.Text("""{"type":"something_else"}"""))
            assertTrue(receiveEvent() is ServerEvent.MoveRejected)

            sendMakeTurn(0, 0)
            val state = receiveState()
            assertEquals(PlayerSymbol.X, state.field[0][0])
        }
    }

    @Test
    fun `occupied cell is not overwritten by the opponent`() = testApplication {
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

            sendMakeTurn(2, 0)
            skipBroadcast()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    receiveState()
                    oReady.complete(Unit)
                    sendMakeTurn(2, 0)
                    assertTrue(receiveEvent() is ServerEvent.MoveRejected)
                    sendMakeTurn(0, 0)
                    val state = receiveState()
                    assertEquals(PlayerSymbol.X, state.field[0][2])
                    assertEquals(PlayerSymbol.O, state.field[0][0])
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast()

            val state = receiveState()
            assertEquals(PlayerSymbol.O, state.field[0][0])
            assertEquals(PlayerSymbol.X, state.field[0][2])

            second.cancel()
        }
    }

    @Test
    fun `winning move is detected immediately`() = testApplication {
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
                    receiveState()
                    oReady.complete(Unit)
                    receiveState()
                    sendMakeTurn(1, 0)
                    receiveState()
                    receiveState()
                    sendMakeTurn(1, 1)
                    receiveState()
                    receiveState()
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast()

            sendMakeTurn(0, 0)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 1)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 2)

            val state = receiveState()
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
            skipBroadcast()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    skipBroadcast()
                    oReady.complete(Unit)
                    awaitCancellation()
                }
            }
            oReady.await()

            client.webSocket("/play") {
                val state = receiveState()
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
                    receiveState()
                    oReady.complete(Unit)
                    receiveState()
                    sendMakeTurn(1, 0)
                    receiveState()
                    receiveState()
                    sendMakeTurn(1, 1)
                    receiveState()
                    receiveState()
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast()

            sendMakeTurn(0, 0)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 1)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 2)

            val state = receiveState()
            assertEquals(PlayerSymbol.X, state.winningPlayer)
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), state.score)

            second.cancel()
        }
    }
}

private suspend fun WebSocketSession.skipBroadcast() {
    receiveEvent()
}

private suspend fun WebSocketSession.receiveEvent(): ServerEvent = withTimeout(5_000) {
    protocolJson.decodeFromString<ServerEvent>((incoming.receive() as Frame.Text).readText())
}

private suspend fun WebSocketSession.receiveState(): GameState = (receiveEvent() as ServerEvent.StateChanged).state

private suspend fun WebSocketSession.sendMakeTurn(x: Int, y: Int) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.MakeTurn(x, y))))
}
