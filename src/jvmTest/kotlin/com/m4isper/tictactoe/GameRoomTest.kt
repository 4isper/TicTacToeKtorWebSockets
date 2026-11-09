package com.m4isper.tictactoe

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.protocol.ServerEvent
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Rooms, spectatorship and names: who a connection becomes inside a named room. */
class GameRoomTest {
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
    fun `spectate watches an empty room and the first player still gets X`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=early&spectate=1") {
            val (joined, state) = receiveHandshake()
            assertNull(joined.symbol) // a deliberate watcher
            assertEquals(emptyList(), state.connectionPlayers) // nobody is playing yet
        }

        client.webSocket("/play?room=early") {
            val (joined, state) = receiveHandshake()
            assertEquals(PlayerSymbol.X, joined.symbol) // the slot was never taken by the spectator
            assertEquals(listOf(PlayerSymbol.X), state.connectionPlayers)
        }
    }

    @Test
    fun `a name in the join url applies itself and a taken one leaves the session unnamed`() = testApplication {
        application {
            testModule()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=nmurl&name=Аня") {
            receiveHandshake()
            awaitStateWhere { it.playerNames[PlayerSymbol.X] == "Аня" }

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=nmurl&name=аня") {
                    val (joined, _) = receiveHandshake()
                    oReady.complete(Unit)
                    assertEquals(PlayerSymbol.O, joined.symbol)
                    sendMakeTurn(99, 99) // a guaranteed private error, whatever the turn order is
                    // the join events may interleave: collect until both the error and a two-player state are seen
                    val events = mutableListOf<ServerEvent>()
                    withTimeout(5_000) {
                        while (events.count { it is ServerEvent.Error } < 2 || events.none { it is ServerEvent.StateChanged }) {
                            events.add(receiveEvent())
                        }
                    }
                    assertTrue(
                        events.any { it is ServerEvent.Error && it.reason == ErrorReason.NAME_TAKEN },
                        "the taken name should be refused: $events",
                    )
                    val state = (events.last { it is ServerEvent.StateChanged } as ServerEvent.StateChanged).state
                    assertEquals(mapOf(PlayerSymbol.X to "Аня"), state.playerNames) // the joiner stays unnamed
                    awaitCancellation()
                }
            }
            oReady.await()

            sendMakeTurn(0, 0) // a move gives O's collector a later state to read

            second.cancel()
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
}
