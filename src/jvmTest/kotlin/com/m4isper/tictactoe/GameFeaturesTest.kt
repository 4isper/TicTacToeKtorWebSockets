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
class GameFeaturesTest {
    @Test
    fun `the rooms endpoint lists open named rooms with their settings`() = testApplication {
        application {
            testModule()
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
            testModule()
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
    fun `a reaction is broadcast to everyone and invalid ones are refused`() = testApplication {
        application {
            testModule()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=rct") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=rct") {
                    receiveHandshake()
                    oReady.complete(Unit)

                    assertEquals(ServerEvent.Reaction(PlayerSymbol.X, "👍"), receiveEvent()) // X's 👍 reaches O
                    sendReact("❤️")
                    assertEquals(ServerEvent.Reaction(PlayerSymbol.O, "❤️"), receiveEvent()) // own echo

                    sendReact("🍕") // not on the whitelist
                    assertTrue(receiveEvent() is ServerEvent.Error)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendReact("👍")
            assertEquals(ServerEvent.Reaction(PlayerSymbol.X, "👍"), receiveEvent()) // own echo
            assertEquals(ServerEvent.Reaction(PlayerSymbol.O, "❤️"), receiveEvent())

            second.cancel()
        }
    }

    @Test
    fun `a second reaction inside the cooldown is dropped quietly`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=rct2") {
            receiveHandshake()

            sendReact("👍")
            sendReact("❤️") // within the cooldown
            sendReact("🍕")
            assertEquals(ServerEvent.Reaction(PlayerSymbol.X, "👍"), receiveEvent())
            assertTrue(receiveEvent() is ServerEvent.Error) // the dropped ❤️ produced nothing before the error
        }
    }

    @Test
    fun `the manifest offers raster icons alongside the svg one`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }

        val manifest = client.get("/manifest.webmanifest").bodyAsText()
        assertTrue("\"/icon-192.png\"" in manifest, manifest)
        assertTrue("\"/icon-512.png\"" in manifest, manifest)

        listOf("/icon-192.png", "/icon-512.png").forEach { src ->
            val icon = client.get(src)
            assertEquals(HttpStatusCode.OK, icon.status)
            val bytes = icon.bodyAsBytes()
            assertTrue(bytes.size > 500, "$src looks empty: ${bytes.size} bytes")
            assertTrue(
                bytes.take(4).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)), // PNG magic
                "$src is not a PNG",
            )
        }
    }

    @Test
    fun `the page adapts to the light scheme`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }

        val css = client.get("/styles.css").bodyAsText()
        assertTrue("--panel" in css, css.take(400))
        assertTrue("prefers-color-scheme: light" in css, css.take(600))

        val page = client.get("/").bodyAsText()
        assertTrue("(prefers-color-scheme: light)" in page, "expected a light theme-color meta: ${page.take(500)}")
    }

    @Test
    fun `the page offers a manual theme override`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }

        val page = client.get("/").bodyAsText()
        assertTrue("id=\"theme\"" in page, "the page should carry a theme toggle: $page")

        val css = client.get("/styles.css").bodyAsText()
        assertTrue("""root[data-theme="light"]""" in css, "a forced light palette should exist: $css")
        assertTrue("""root:not([data-theme="dark"])""" in css, "the auto light palette should spare an explicit dark: $css")
    }

    @Test
    fun `the service worker falls back to an offline page`() = testApplication {
        application {
            testModule()
        }
        val client = createClient { }

        val sw = client.get("/sw.js").bodyAsText()
        assertTrue("caches.open" in sw, sw)
        assertTrue("""e.request.mode === "navigate"""" in sw, sw)

        val offline = client.get("/offline")
        assertEquals(HttpStatusCode.OK, offline.status)
        assertTrue("Нет соединения" in offline.bodyAsText())
    }

    @Test
    fun `a message flood is dropped quietly`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=flood") {
            receiveHandshake()

            repeat(25) { sendSetName("Спамер") } // 20 per second is the budget
            repeat(20) { assertTrue(receiveEvent() is ServerEvent.StateChanged) }

            delay(1_100) // the window resets
            sendMakeTurn(0, 0)
            val state = receiveState()
            assertEquals(PlayerSymbol.X, state.field[0][0]) // the connection still plays
        }
    }

    @Test
    fun `an approved takeback reverts the last move`() = testApplication {
        application {
            testModule()
        }
        val xClient = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        xClient.webSocket("/play?room=undo") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=undo") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    receiveState() // X's opening move
                    val offered = receiveState() // X's takeback request
                    assertEquals(PlayerSymbol.X, offered.undoOfferBy)
                    sendRequestUndo()
                    val reverted = receiveState()
                    assertNull(reverted.field[0][0])
                    assertEquals(PlayerSymbol.X, reverted.playerAtTurn)
                    awaitCancellation()
                }
            }
            oReady.await()

            skipBroadcast() // [X, O]

            sendMakeTurn(0, 0)
            skipBroadcast() // own broadcast
            sendRequestUndo()
            val offered = receiveState()
            assertEquals(PlayerSymbol.X, offered.undoOfferBy)

            val reverted = receiveState()
            assertNull(reverted.field[0][0])
            assertEquals(PlayerSymbol.X, reverted.playerAtTurn)

            second.cancel()
        }
    }

    @Test
    fun `bot=2 turns the room into an exhibition where joiners watch`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=expo&bot=2") {
            val (joined, _) = receiveHandshake()
            assertNull(joined.symbol) // both slots belong to the bots

            val secondMove = awaitStateWhere { it.field.sumOf { row -> row.count { it != null } } >= 2 } // both played
            assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), secondMove.connectionPlayers)
        }
    }

    @Test
    fun `an out of range clock is refused before joining`() = testApplication {
        application {
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }
        val oClient = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=ck&clock=29") {
            assertEquals(ErrorReason.INVALID_CLOCK, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=ck&clock=3601") {
            assertEquals(ErrorReason.INVALID_CLOCK, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=ck&clock=abc") {
            assertEquals(ErrorReason.INVALID_CLOCK, (receiveEvent() as ServerEvent.Error).reason)
        }

        client.webSocket("/play?room=ck&clock=300") {
            receiveHandshake()

            val oReady = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext)
            val second = scope.launch {
                oClient.webSocket("/play?room=ck&clock=300") {
                    receiveHandshake()
                    oReady.complete(Unit)
                    awaitCancellation()
                }
            }
            oReady.await()

            val both = receiveState() // the broadcast that O has joined
            assertTrue(both.clocks.getValue(PlayerSymbol.X) in 299_900..300_000, "the budget rides the state")
            assertTrue(both.clocks.getValue(PlayerSymbol.O) <= 300_000)

            second.cancel()
        }
    }

    @Test
    fun `bot fills the second slot and answers with a move`() = testApplication {
        application {
            testModule()
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
            testModule()
        }
        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/play?room=b9&bot=3") {
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
            testModule()
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
            testModule()
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
            testModule(reconnectGraceMs = 500)
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
