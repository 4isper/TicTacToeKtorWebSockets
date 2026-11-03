package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class GameLobbyTest {

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
    fun `anonymous matching pairs only identical settings`() {
        val lobby = GameLobby()
        val first = assertNotNull(lobby.join(FakeSession(), boardSize = 5, winLength = 4))
        val otherSize = assertNotNull(lobby.join(FakeSession(), boardSize = 3, winLength = 3))
        val otherWin = assertNotNull(lobby.join(FakeSession(), boardSize = 5, winLength = 5))
        val otherTurn = assertNotNull(lobby.join(FakeSession(), boardSize = 5, winLength = 4, turnSeconds = 60))
        assertNotSame(first.game, otherSize.game) // their settings would otherwise be silently ignored
        assertNotSame(first.game, otherWin.game)
        assertNotSame(first.game, otherTurn.game)

        val partner = assertNotNull(lobby.join(FakeSession(), boardSize = 5, winLength = 4))
        assertSame(first.game, partner.game)
        assertEquals(PlayerSymbol.O, partner.symbol) // paired into the matching game

        val third = assertNotNull(lobby.join(FakeSession(), boardSize = 5, winLength = 4))
        assertNotSame(first.game, third.game) // the full pair stays closed

        lobby.shutdownAll()
    }

    @Test
    fun `bot games never pair with each other`() {
        val lobby = GameLobby()
        val first = assertNotNull(lobby.join(FakeSession(), boardSize = 3, winLength = 3, botLevel = BotLevel.NORMAL))
        val easy = assertNotNull(lobby.join(FakeSession(), boardSize = 3, winLength = 3, botLevel = BotLevel.EASY))
        assertNotSame(first.game, easy.game) // a different level is a different game
        val second = assertNotNull(lobby.join(FakeSession(), boardSize = 3, winLength = 3, botLevel = BotLevel.NORMAL))
        assertNotSame(first.game, second.game)
        assertEquals(2, first.game.currentState().connectionPlayers.size) // the bot fills the second slot

        lobby.shutdownAll()
    }
}
