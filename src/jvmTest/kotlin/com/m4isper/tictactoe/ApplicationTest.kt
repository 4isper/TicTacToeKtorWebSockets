package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
            assertEquals(PlayerSymbol.X, state.playerAtTurn)
            assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
        }
    }

    @Test
    fun `state events carry every field explicitly`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play") {
            val texts = (1..2).map { (incoming.receive() as Frame.Text).readText() }
            val state = texts.single { it.contains("\"type\":\"state\"") }
            assertTrue(state.contains("\"playerAtTurn\":\"X\""), state)
            assertTrue(state.contains("\"winningPlayer\":null"), state)
            assertTrue(state.contains("\"winningCells\":[]"), state)
            assertTrue(state.contains("\"isBoardFull\":false"), state)
            assertTrue(state.contains("\"roundRestartInMs\":null"), state)
            assertTrue(state.contains("\"rematchPlayers\":[]"), state)
            assertTrue(state.contains("\"score\":{\"X\":0,\"O\":0}"), state)
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
            receiveHandshake()

            send(Frame.Text("not json at all"))
            assertTrue(receiveEvent() is ServerEvent.Error)

            send(Frame.Text("""{"type":"something_else"}"""))
            assertTrue(receiveEvent() is ServerEvent.Error)

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
            receiveHandshake()

            sendMakeTurn(2, 0)
            skipBroadcast()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    sendMakeTurn(2, 0)
                    assertTrue(receiveEvent() is ServerEvent.Error)
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
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    receiveHandshake()
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
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                client.webSocket("/play") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    awaitCancellation()
                }
            }
            oReady.await()

            client.webSocket("/play") {
                val (joined, state) = receiveHandshake()
                assertEquals(PlayerSymbol.X, joined.symbol)
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
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    receiveHandshake()
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

    @Test
    fun `player reconnects with a token and keeps the symbol`() = testApplication {
        application {
            module(reconnectGraceMs = 500)
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }
        var token: String? = null

        xClient.webSocket("/play") {
            val (joined, _) = receiveHandshake()
            token = joined.token

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    skipBroadcast()
                    sendMakeTurn(1, 0)
                    skipBroadcast()
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast()
            sendMakeTurn(0, 0)
            skipBroadcast()
            skipBroadcast()
        }

        xClient.webSocket("/play") {
            receiveHandshake()
            sendReconnect(token!!)
            val state = receiveState()
            assertEquals(PlayerSymbol.X, state.playerAtTurn)
            assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), state.connectionPlayers)
            assertEquals(PlayerSymbol.X, state.field[0][0])
            assertEquals(PlayerSymbol.O, state.field[0][1])

            sendMakeTurn(0, 1)
            skipBroadcast()
        }
    }

    @Test
    fun `a replaced session can no longer play for the player`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play") {
            val (joined, _) = receiveHandshake()

            val reconnected = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val replacement = scope.launch {
                xClient.webSocket("/play") {
                    receiveHandshake()
                    sendReconnect(joined.token)
                    // wait for the server to confirm the rebind before unblocking the zombie
                    assertTrue(receiveEvent() is ServerEvent.StateChanged)
                    reconnected.complete(Unit)
                    awaitCancellation()
                }
            }
            reconnected.await()
            // drain the broadcast about the replacement's short-lived join
            skipBroadcast()

            // the outer session lost the X slot to the reconnected one
            sendMakeTurn(0, 0)
            val event = receiveEvent()
            assertTrue(event is ServerEvent.Error)

            replacement.cancel()
        }
    }

    @Test
    fun `slot is freed when the grace expires and the old token dies`() = testApplication {
        application {
            module(reconnectGraceMs = 500)
        }
        val firstClient = createClient {
            install(WebSockets)
        }
        val secondClient = createClient {
            install(WebSockets)
        }
        val thirdClient = createClient {
            install(WebSockets)
        }
        var firstToken: String? = null
        val secondJoined = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()

        firstClient.webSocket("/play") {
            val (joined, _) = receiveHandshake()
            firstToken = joined.token
        }

        val holder = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            secondClient.webSocket("/play") {
                receiveHandshake()
                secondJoined.complete(Unit)
                releaseSecond.await()
            }
        }
        secondJoined.await()

        delay(700)

        thirdClient.webSocket("/play") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
            assertEquals(listOf(PlayerSymbol.O, PlayerSymbol.X), state.connectionPlayers)

            sendReconnect(firstToken!!)
            assertTrue(receiveEvent() is ServerEvent.Error)
        }

        releaseSecond.complete(Unit)
        holder.join()
    }

    @Test
    fun `named room pairs two players and rejects the third`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }
        val thirdClient = createClient {
            install(WebSockets)
        }
        val betaClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=alpha") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
            assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
        }

        oClient.webSocket("/play?room=alpha") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.O, joined.symbol)
            assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), state.connectionPlayers)
        }

        thirdClient.webSocket("/play?room=alpha") {
            assertTrue(receiveEvent() is ServerEvent.Error)
        }

        betaClient.webSocket("/play?room=beta") {
            val (joined, _) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
        }
    }

    @Test
    fun `room creator picks the board size and latecomers inherit it`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=big&size=5") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
            assertEquals(5, state.field.size)

            sendMakeTurn(4, 4)
            skipBroadcast()
        }

        client.webSocket("/play?room=big") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.O, joined.symbol)
            assertEquals(5, state.field.size)
            assertEquals(PlayerSymbol.X, state.field[4][4])
        }
    }

    @Test
    fun `an out of range size is refused before joining`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=tiny&size=2") {
            assertEquals(ErrorReason.INVALID_BOARD_SIZE, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=tiny&size=abc") {
            assertEquals(ErrorReason.INVALID_BOARD_SIZE, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=tiny") {
            val (_, state) = receiveHandshake()
            assertEquals(3, state.field.size)
        }
    }

    @Test
    fun `four in a row wins on the requested board and reports the run`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=w5&size=5&win=4") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=w5") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    receiveState() // X opens, only then it is O's turn
                    sendMakeTurn(1, 0)
                    receiveState()
                    receiveState()
                    sendMakeTurn(1, 1)
                    receiveState()
                    receiveState()
                    sendMakeTurn(1, 2)
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
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 3)

            val event = receiveEvent()
            assertTrue(event is ServerEvent.StateChanged, "expected state, got $event")
            val state = event.state
            assertEquals(PlayerSymbol.X, state.winningPlayer)
            assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(0, 3)), state.winningCells)
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), state.score)

            second.cancel()
        }
    }

    @Test
    fun `an out of range win length is refused before joining`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=w2&win=2") {
            assertEquals(ErrorReason.INVALID_WIN_LENGTH, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=w2&win=6") {
            assertEquals(ErrorReason.INVALID_WIN_LENGTH, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=w2&win=abc") {
            assertEquals(ErrorReason.INVALID_WIN_LENGTH, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=w2") {
            val (_, state) = receiveHandshake()
            assertEquals(3, state.field.size)
        }
    }

    @Test
    fun `rematch resets the round when both players ask before the timer`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=rm") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=rm") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    receiveState() // X opens, only then it is O's turn
                    sendMakeTurn(1, 0)
                    receiveState()
                    receiveState()
                    sendMakeTurn(1, 1)
                    receiveState()
                    receiveState() // X's winning move
                    val xRematch = receiveState() // X asks first, O mirrors after seeing it
                    assertEquals(listOf(PlayerSymbol.X), xRematch.rematchPlayers)
                    sendRequestRematch()
                    val reset = receiveState()
                    assertNull(reset.winningPlayer)
                    assertNull(reset.roundRestartInMs)
                    assertEquals(emptyList(), reset.rematchPlayers)
                    assertNull(reset.field[0][0])
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
            val win = receiveState()
            assertNotNull(win.roundRestartInMs)

            sendRequestRematch()
            val afterFirst = receiveState()
            assertEquals(listOf(PlayerSymbol.X), afterFirst.rematchPlayers)

            val reset = receiveState()
            assertNull(reset.winningPlayer)
            assertNull(reset.roundRestartInMs)
            assertEquals(emptyList(), reset.rematchPlayers)
            assertNull(reset.field[0][0])
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), reset.score)

            second.cancel()
        }
    }

    @Test
    fun `room is freed after everyone leaves`() = testApplication {
        application {
            module(reconnectGraceMs = 500)
        }
        val firstClient = createClient {
            install(WebSockets)
        }
        val secondClient = createClient {
            install(WebSockets)
        }
        val nextClient = createClient {
            install(WebSockets)
        }
        var firstToken: String? = null

        firstClient.webSocket("/play?room=alpha") {
            val (joined, _) = receiveHandshake()
            firstToken = joined.token
            sendMakeTurn(0, 0)
            skipBroadcast()
        }

        secondClient.webSocket("/play?room=alpha") {
            receiveHandshake()
        }

        delay(700)

        nextClient.webSocket("/play?room=alpha") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
            assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
            assertNull(state.field[0][0])

            sendReconnect(firstToken!!)
            assertTrue(receiveEvent() is ServerEvent.Error)
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

private suspend fun WebSocketSession.receiveHandshake(): Pair<ServerEvent.Joined, GameState> {
    val events = listOf(receiveEvent(), receiveEvent())
    val joined = events.filterIsInstance<ServerEvent.Joined>().single()
    val state = events.filterIsInstance<ServerEvent.StateChanged>().single().state
    return joined to state
}

private suspend fun WebSocketSession.sendReconnect(token: String) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.Reconnect(token))))
}

private suspend fun WebSocketSession.sendMakeTurn(x: Int, y: Int) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.MakeTurn(x, y))))
}

private suspend fun WebSocketSession.sendRequestRematch() {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestRematch)))
}
