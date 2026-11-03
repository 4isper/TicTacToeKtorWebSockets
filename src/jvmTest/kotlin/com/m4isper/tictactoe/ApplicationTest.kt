package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
    fun `health endpoint answers ok`() = testApplication {
        application {
            module()
        }
        val client = createClient { }
        assertEquals("ok", client.get("/health").bodyAsText())
    }

    @Test
    fun `the page is rendered in the requested language`() = testApplication {
        application {
            module()
        }
        val client = createClient { }

        val russian = client.get("/?room=lang").bodyAsText()
        assertTrue("Скопировать ссылку" in russian, "default page should be russian, got: $russian")
        assertTrue("lang=\"ru\"" in russian)

        val english = client.get("/?room=lang&lang=en").bodyAsText()
        assertTrue("Copy link" in english, "lang=en should render english")
        assertTrue("lang=\"en\"" in english)
        assertTrue("Tic-tac-toe" in english)
    }

    @Test
    fun `named wins reach the leaderboard endpoint`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=lb") {
            receiveHandshake()
            sendSetName("Аня")

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=lb") {
                    receiveHandshake()
                    sendSetName("Боря")
                    oReady.complete(Unit)
                    awaitStateWhere { it.playerAtTurn == PlayerSymbol.O } // X's opening
                    sendMakeTurn(1, 0)
                    awaitStateWhere { it.playerAtTurn == PlayerSymbol.O } // X's second move
                    sendMakeTurn(1, 1)
                    awaitCancellation()
                }
            }
            oReady.await()

            awaitStateWhere { it.playerNames[PlayerSymbol.O] == "Боря" }
            sendMakeTurn(0, 0)
            awaitStateWhere { it.playerAtTurn == PlayerSymbol.X }
            sendMakeTurn(0, 1)
            awaitStateWhere { it.playerAtTurn == PlayerSymbol.X }
            sendMakeTurn(0, 2)
            awaitStateWhere { it.winningPlayer == PlayerSymbol.X }

            second.cancel()
        }

        val board = client.get("/leaderboard").bodyAsText()
        assertTrue("Аня" in board, "leaderboard should contain Аня: $board")
        assertTrue("Боря" in board, "leaderboard should contain Боря: $board")

        val profile = client.get("/player?name=Аня")
        assertEquals(HttpStatusCode.OK, profile.status)
        assertTrue("Боря" in profile.bodyAsText(), "history should name the opponent: ${profile.bodyAsText()}")
        assertTrue("WIN" in profile.bodyAsText())
        assertTrue(
            "\"moves\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":0},{\"x\":0,\"y\":1},{\"x\":1,\"y\":1},{\"x\":0,\"y\":2}]" in profile.bodyAsText(),
            "the record should carry the replay: ${profile.bodyAsText()}",
        )
        assertTrue("\"boardSize\":3" in profile.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.get("/player?name=Кто-то").status)
    }

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
            assertTrue(state.contains("\"playerNames\":{}"), state)
            assertTrue(state.contains("\"turnRemainingMs\":null"), state)
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
            val (joined, state) = receiveHandshake()
            assertNull(joined.symbol) // third comer watches as a spectator
            assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), state.connectionPlayers)
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
    fun `a third connection to a named room watches as a spectator`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }
        val spectatorClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=spec") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val spectatorReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=spec") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    awaitCancellation()
                }
            }
            oReady.await() // the spectator must see both players on arrival

            val third = scope.launch {
                spectatorClient.webSocket("/play?room=spec") {
                    val (joined, state) = receiveHandshake()
                    assertNull(joined.symbol)
                    assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), state.connectionPlayers)
                    spectatorReady.complete(Unit)

                    sendMakeTurn(0, 0)
                    // the rejection and X's real move may arrive in either order
                    val events = listOf(receiveEvent(), receiveEvent())
                    assertTrue(events.any { it is ServerEvent.Error }) // spectators cannot play
                    assertTrue(events.any { it is ServerEvent.StateChanged }) // X's move reaches the spectator
                    awaitCancellation()
                }
            }
            spectatorReady.await()

            skipBroadcast() // [X, O]

            sendMakeTurn(0, 0)
            skipBroadcast() // own broadcast

            second.cancel()
            third.cancel()
        }
    }

    @Test
    fun `players name themselves and invalid names are refused`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=nm") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=nm") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    val named = receiveState() // X's set_name broadcast
                    assertEquals(mapOf(PlayerSymbol.X to "Аня"), named.playerNames)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendSetName("Аня")
            val named = receiveState()
            assertEquals(mapOf(PlayerSymbol.X to "Аня"), named.playerNames)

            sendSetName("   ") // blank after trim
            assertTrue(receiveEvent() is ServerEvent.Error)
            sendSetName("x".repeat(21)) // too long
            assertTrue(receiveEvent() is ServerEvent.Error)

            second.cancel()
        }
    }

    @Test
    fun `an out of range turn time is refused before joining`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=t9&turn=4") {
            assertEquals(ErrorReason.INVALID_TURN_TIME, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=t9&turn=601") {
            assertEquals(ErrorReason.INVALID_TURN_TIME, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=t9&turn=abc") {
            assertEquals(ErrorReason.INVALID_TURN_TIME, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=t9") {
            val (_, state) = receiveHandshake()
            assertEquals(3, state.field.size)
        }
    }

    @Test
    fun `the turn timer honors the requested seconds`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=tt&turn=45") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=tt&turn=45") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    awaitStateWhere { it.playerAtTurn == PlayerSymbol.O } // X's opening
                    sendMakeTurn(1, 0)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendMakeTurn(0, 0)
            val state = receiveState()
            val left = state.turnRemainingMs
            assertTrue(left != null && left in 30_001..45_000, "turn timer was $left, not the requested 45s")

            second.cancel()
        }
    }

    @Test
    fun `both players asking for a draw ends the round`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=dr") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=dr") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    val offered = receiveState() // X's offer broadcast
                    assertEquals(listOf(PlayerSymbol.X), offered.drawPlayers)
                    sendRequestDraw()
                    val agreed = receiveState()
                    assertTrue(agreed.isDrawAgreed)
                    assertNotNull(agreed.roundRestartInMs)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendRequestDraw()
            val offered = receiveState()
            assertEquals(listOf(PlayerSymbol.X), offered.drawPlayers)

            val agreed = receiveState()
            assertTrue(agreed.isDrawAgreed)
            assertNull(agreed.winningPlayer)
            assertNotNull(agreed.roundRestartInMs)

            second.cancel()
        }
    }

    @Test
    fun `the rooms endpoint lists open named rooms with their settings`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=listme&size=5&win=4&bot=hard&turn=60") {
            receiveHandshake()

            val body = client.get("/rooms").bodyAsText()
            assertTrue("\"listme\"" in body, body)
            assertTrue("\"boardSize\":5" in body, body)
            assertTrue("\"winLength\":4" in body, body)
            assertTrue("\"botLevel\":\"hard\"" in body, body)
            assertTrue("\"turnSeconds\":60" in body, body)
        }
    }

    @Test
    fun `the page is installable`() = testApplication {
        application {
            module()
        }
        val client = createClient { }

        val page = client.get("/?lang=en").bodyAsText()
        assertTrue("/manifest.webmanifest" in page, "the page should link the manifest: $page")
        assertTrue("theme-color" in page, "the page should carry the theme color")

        val manifest = client.get("/manifest.webmanifest")
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertTrue("\"start_url\":\"/\"" in manifest.bodyAsText(), manifest.bodyAsText())
        assertTrue("\"display\":\"standalone\"" in manifest.bodyAsText())

        val sw = client.get("/sw.js")
        assertEquals(HttpStatusCode.OK, sw.status)
        assertTrue("""addEventListener("fetch"""" in sw.bodyAsText(), sw.bodyAsText())

        val icon = client.get("/icon.svg")
        assertEquals(HttpStatusCode.OK, icon.status)
        assertTrue("<svg" in icon.bodyAsText())
    }

    @Test
    fun `bot fills the second slot and answers with a move`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=vsbot&bot=1") {
            val (joined, _) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)

            sendMakeTurn(0, 0)
            val botReply = awaitStateWhere { it.field.sumOf { row -> row.count { it != null } } >= 2 }
            assertEquals(2, botReply.field.sumOf { row -> row.count { it != null } })
            assertTrue(botReply.field.any { row -> row.contains(PlayerSymbol.O) }) // the bot played
            assertEquals(PlayerSymbol.X, botReply.playerAtTurn) // and handed the turn back
        }
    }

    @Test
    fun `an unknown bot level is refused before joining`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=b9&bot=2") {
            assertEquals(ErrorReason.INVALID_BOT_LEVEL, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=b9&bot=strong") {
            assertEquals(ErrorReason.INVALID_BOT_LEVEL, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=b9&bot=normal") {
            val (joined, _) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)
        }
    }

    @Test
    fun `the easy bot fills the second slot and answers with a move`() = testApplication {
        application {
            module()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=vseasy&bot=easy") {
            val (joined, _) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol)

            sendMakeTurn(0, 0)
            val botReply = awaitStateWhere { it.field.sumOf { row -> row.count { it != null } } >= 2 }
            assertEquals(2, botReply.field.sumOf { row -> row.count { it != null } })
            assertTrue(botReply.field.any { row -> row.contains(PlayerSymbol.O) }) // the bot played
        }
    }

    @Test
    fun `a second human displaces the bot`() = testApplication {
        application {
            module()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=vsbot2&bot=1") {
            receiveHandshake()

            // the opening: X moves, the bot answers
            sendMakeTurn(0, 0)
            awaitStateWhere { it.field.sumOf { row -> row.count { it != null } } >= 2 }

            // a human takes the bot's O slot
            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=vsbot2") {
                    val (joined, _) = receiveHandshake()
                    assertEquals(PlayerSymbol.O, joined.symbol)
                    oReady.complete(Unit)
                    awaitStateWhere { it.playerAtTurn == PlayerSymbol.O } // X's move below
                    sendMakeTurn(2, 1)
                    awaitCancellation()
                }
            }
            oReady.await()

            sendMakeTurn(2, 2)
            val humanReply = awaitStateWhere { it.field[1][2] == PlayerSymbol.O }
            assertEquals(PlayerSymbol.X, humanReply.playerAtTurn)

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

/** Reads states until one matches, absorbing anything broadcast in between. */
private suspend fun WebSocketSession.awaitStateWhere(
    timeoutMs: Long = 5_000,
    predicate: (GameState) -> Boolean,
): GameState = withTimeout(timeoutMs) {
    while (true) {
        val state = receiveState()
        if (predicate(state)) return@withTimeout state
    }
    @Suppress("UNREACHABLE_CODE")
    receiveState()
}

private suspend fun WebSocketSession.receiveHandshake(): Pair<ServerEvent.Joined, GameState> {
    var joined: ServerEvent.Joined? = null
    var state: GameState? = null
    withTimeout(5_000) {
        while (joined == null || state == null) {
            when (val event = receiveEvent()) {
                is ServerEvent.Joined -> joined = event
                is ServerEvent.StateChanged -> state = event.state
                else -> {}
            }
        }
    }
    return joined!! to state!!
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

private suspend fun WebSocketSession.sendRequestDraw() {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestDraw)))
}

private suspend fun WebSocketSession.sendSetName(name: String) {
    send(Frame.Text(protocolJson.encodeToString<ClientMessage>(ClientMessage.SetName(name))))
}
