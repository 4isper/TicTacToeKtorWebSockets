package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.stats.JsonScoreStore
import com.m4isper.tictactoe.stats.Scoreboard
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
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
import java.nio.file.Files
import kotlin.test.*

/** A module whose scoreboard lives in a temp file, so the tests never touch the real leaderboard. */
class GameFlowTest {
    @Test
    fun `named room pairs two players and rejects the third`() = testApplication {
        application {
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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

    /** O's side of `the opening turn alternates between rounds`: mirrors the rematches, wins round 2. */
    private suspend fun WebSocketSession.playAlternatingOSide() {
        // round 1: O answers X's column
        receiveState()
        sendMakeTurn(1, 0)
        receiveState()
        receiveState()
        sendMakeTurn(1, 1)
        receiveState()
        receiveState()
        // X asks first, O mirrors: the reset must hand the opening to O
        receiveState()
        sendRequestRematch()
        assertEquals(PlayerSymbol.O, receiveState().playerAtTurn)

        // round 2: O opens and wins with its own column
        sendMakeTurn(1, 0)
        receiveState()
        receiveState()
        sendMakeTurn(1, 1)
        receiveState()
        receiveState()
        sendMakeTurn(1, 2)
        assertEquals(PlayerSymbol.O, receiveState().winningPlayer)
        // O asks first, X mirrors: the opening returns to X
        receiveState()
        sendRequestRematch()
        assertEquals(PlayerSymbol.X, receiveState().playerAtTurn)
        awaitCancellation()
    }

    @Test
    fun `the opening turn alternates between rounds`() = testApplication {
        application {
            testModule()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=alt") {
            receiveHandshake()
            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=alt") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    playAlternatingOSide()
                }
            }
            oReady.await()

            skipBroadcast()

            // round 1: X opens and wins with the first column
            sendMakeTurn(0, 0)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 1)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(0, 2)
            val win = receiveState()
            assertEquals(PlayerSymbol.X, win.winningPlayer)

            sendRequestRematch()
            skipBroadcast() // the offer broadcast
            val reset = receiveState()
            assertEquals(PlayerSymbol.O, reset.playerAtTurn)
            assertNull(reset.field[0][0]) // a fresh board

            // round 2: O opens, X answers; O makes the last move this time
            receiveState()
            sendMakeTurn(2, 0)
            skipBroadcast()
            skipBroadcast()
            sendMakeTurn(2, 1)
            skipBroadcast()
            val loss = receiveState()
            assertEquals(PlayerSymbol.O, loss.winningPlayer)

            sendRequestRematch()
            skipBroadcast()
            val reset2 = receiveState()
            assertEquals(PlayerSymbol.X, reset2.playerAtTurn)
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 1), reset2.score)

            second.cancel()
        }
    }

    @Test
    fun `a dropped player is marked away until the grace expires`() = testApplication {
        application {
            testModule(reconnectGraceMs = 300)
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=away") {
            receiveHandshake()
            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=away") {
                    receiveHandshake()
                    oReady.complete(Unit)
                } // the block ends: the socket drops into the reconnect grace
            }
            oReady.await()

            // the drop is announced at once, while the slot is still held
            val away = awaitStateWhere { it.awayPlayers.isNotEmpty() }
            assertEquals(listOf(PlayerSymbol.O), away.awayPlayers)
            assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), away.connectionPlayers)

            // after the grace the slot is freed and the away mark is gone
            val freed = awaitStateWhere { it.connectionPlayers == listOf(PlayerSymbol.X) }
            assertTrue(freed.awayPlayers.isEmpty())

            second.cancel()
        }
    }

    @Test
    fun `a third connection to a named room watches as a spectator`() = testApplication {
        application {
            testModule()
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

                    sendRequestResign()
                    assertEquals(ErrorReason.NOT_YOUR_TURN, (receiveEvent() as ServerEvent.Error).reason)
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
    fun `a resignation forfeits the round and counts as a rated loss`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=rg") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=rg") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    awaitStateWhere { it.playerNames[PlayerSymbol.X] == "Аня" }
                    sendSetName("Боря")
                    awaitStateWhere { it.playerNames[PlayerSymbol.O] == "Боря" }
                    sendRequestResign()
                    val resigned = receiveState()
                    assertEquals(PlayerSymbol.X, resigned.winningPlayer)
                    assertNotNull(resigned.roundRestartInMs)

                    // asking again is refused — the round is already over
                    sendRequestResign()
                    assertEquals(ErrorReason.ROUND_IS_OVER, (receiveEvent() as ServerEvent.Error).reason)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendSetName("Аня")
            awaitStateWhere { it.playerNames[PlayerSymbol.O] == "Боря" }

            val resigned = awaitStateWhere { it.winningPlayer == PlayerSymbol.X }
            assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), resigned.score)
            assertNotNull(resigned.roundRestartInMs)

            second.cancel()
        }

        val winner = client.get("/player?name=Аня").bodyAsText()
        assertTrue("WIN" in winner, "the resignation should pay the opponent: $winner")
        val resigner = client.get("/player?name=Боря").bodyAsText()
        assertTrue("LOSS" in resigner, "the resignation should count as a loss: $resigner")
    }
}
