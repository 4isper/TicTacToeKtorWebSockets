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
class ApplicationTest {
    @Test
    fun `health endpoint answers ok`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }
        assertEquals("ok", client.get("/health").bodyAsText())
    }

    @Test
    fun `the page is rendered in the requested language`() = testApplication {
        application {
            testModule()
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
    fun `the page offers a quick play link and a create-game form`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }

        val russian = client.get("/").bodyAsText()
        assertTrue("id=\"quick-play\"" in russian, "quick play link missing, got: $russian")
        assertTrue("id=\"player-search\"" in russian, "player search missing, got: $russian")
        assertTrue("<form action=\"/\"" in russian, "create-game form missing, got: $russian")
        listOf("room", "size", "win", "turn", "clock", "bot").forEach { field ->
            assertTrue("name=\"$field\"" in russian, "form field $field missing")
        }

        val english = client.get("/?lang=en").bodyAsText()
        assertTrue("Quick play" in english)
        assertTrue("Create a game" in english)
        // an explicit lang rides the form submission as a hidden field
        assertTrue("name=\"lang\"" in english)
    }

    @Test
    fun `named wins reach the leaderboard endpoint`() = testApplication {
        application {
            testModule()
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
        assertTrue("\"rating\":1020" in profile.bodyAsText(), "the win should pay elo: ${profile.bodyAsText()}")
        assertTrue("\"ratingAfter\":1020" in profile.bodyAsText())
        assertTrue(
            "\"moves\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":0},{\"x\":0,\"y\":1},{\"x\":1,\"y\":1},{\"x\":0,\"y\":2}]" in profile.bodyAsText(),
            "the record should carry the replay: ${profile.bodyAsText()}",
        )
        assertTrue("\"boardSize\":3" in profile.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, client.get("/player?name=Кто-то").status)
    }

    @Test
    fun `a keyed name cannot be taken after its owner leaves`() = testApplication {
        application {
            testModule(reconnectGraceMs = 50)
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?name=Аня&key=key-anya") {
            receiveHandshake()
            awaitStateWhere { it.playerNames[PlayerSymbol.X] == "Аня" }
        }
        delay(150) // the owner's grace expired: the session is gone

        client.webSocket("/play?name=Аня&key=key-stranger") {
            // the state broadcast is async and races the refusal: read until one of them wins
            val outcome = withTimeout(5_000) {
                var event: ServerEvent
                do {
                    event = receiveEvent()
                } while (event != ServerEvent.Error(ErrorReason.NAME_TAKEN) &&
                    !(event is ServerEvent.StateChanged && event.state.playerNames.containsValue("Аня"))
                )
                event
            }
            assertEquals(ServerEvent.Error(ErrorReason.NAME_TAKEN), outcome)
        }
        delay(150)

        client.webSocket("/play?name=Аня&key=key-anya") {
            receiveHandshake()
            awaitStateWhere { it.playerNames[PlayerSymbol.X] == "Аня" } // the owner reclaimed it
        }
    }

    @Test
    fun `first player receives game state after connect`() = testApplication {
        application {
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule()
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
            testModule(reconnectGraceMs = 500)
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
            scope.launch {
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
            // the rebind is announced with a fresh Joined; the reattach broadcast may race it
            val events = listOf(receiveEvent(), receiveEvent())
            assertTrue(events.any { it == ServerEvent.Joined(token, PlayerSymbol.X) }, "reclaim Joined, got: $events")
            val state = (events.first { it is ServerEvent.StateChanged } as ServerEvent.StateChanged).state
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
            testModule()
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
                    val confirm = receiveEvent()
                    assertTrue(confirm is ServerEvent.Joined || confirm is ServerEvent.StateChanged, "rebind confirm, got: $confirm")
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
            testModule(reconnectGraceMs = 500)
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
}
