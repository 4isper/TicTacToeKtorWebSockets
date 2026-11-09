package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LobbyPersistenceTest {

    @Suppress("EmptyFunctionBlock")
    private open class FakeSession : WebSocketSession {
        override val coroutineContext = SupervisorJob()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun flush() {}
        override fun terminate() {}
    }

    @Test
    fun `a named room and its name ownership survive a restart`() = runBlocking {
        val file = Files.createTempFile("lobby-restart", ".json").toFile()
        val first = GameLobby(store = JsonLobbyStore(file), persistIntervalMs = 50)
        val session = FakeSession()
        val joined = assertNotNull(first.join(session, JoinSettings(room = "alpha")))
        assertTrue(first.claimName(joined.token, "Аня", playerKey = "key-anya"))
        val symbol = assertNotNull(joined.symbol)
        joined.game.setName(symbol, "Аня")
        joined.game.finishTurn(symbol, session, 0, 0)
        first.join(FakeSession(), JoinSettings()) // an anonymous game: never persisted
        delay(150) // let the periodic snapshot capture the room
        first.shutdownAll()

        // a fresh lobby reading the same store is a server restart
        val second = GameLobby(store = JsonLobbyStore(file), persistIntervalMs = 50)

        val stranger = assertNotNull(second.join(FakeSession(), JoinSettings(room = "beta")))
        assertFalse(second.claimName(stranger.token, "Аня", playerKey = "key-stranger")) // the key still owns the name

        val rejoin = assertNotNull(second.join(FakeSession(), JoinSettings(room = "alpha")))
        assertEquals(PlayerSymbol.X, rejoin.game.currentState().field[0][0]) // the played move resumed
        assertEquals(PlayerSymbol.O, rejoin.game.currentState().playerAtTurn)
        assertTrue(second.roomSnapshots().any { it.name == "alpha" }) // listed again once a player is back
        second.shutdownAll()
    }

    @Test
    fun `a lobby without a store keeps working in memory`() {
        val lobby = GameLobby()
        val joined = assertNotNull(lobby.join(FakeSession(), JoinSettings(room = "alpha")))
        assertEquals(PlayerSymbol.X, joined.symbol)
        lobby.shutdownAll()
    }
}
