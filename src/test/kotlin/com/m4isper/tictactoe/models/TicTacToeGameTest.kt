package com.m4isper.tictactoe.models

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
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
class TicTacToeGameTest {

    @Suppress("EmptyFunctionBlock")
    private class FakeSession : WebSocketSession {
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
    fun `illegal moves are rejected with a reason`() = runBlocking {
        val game = TicTacToeGame()
        game.connectPlayer(FakeSession())
        game.connectPlayer(FakeSession())

        assertEquals("cell out of bounds", game.finishTurn(PlayerSymbol.X, 3, 0))
        assertEquals("not your turn", game.finishTurn(PlayerSymbol.O, 0, 0))
        assertNull(game.finishTurn(PlayerSymbol.X, 0, 0))
        assertEquals("cell occupied", game.finishTurn(PlayerSymbol.O, 0, 0))

        game.shutdown()
    }

    @Test
    fun `score accumulates across rounds and survives the auto restart`() = runBlocking {
        val game = TicTacToeGame(roundRestartDelayMs = 50)
        game.connectPlayer(FakeSession())
        game.connectPlayer(FakeSession())

        game.finishTurn(PlayerSymbol.X, 0, 0)
        game.finishTurn(PlayerSymbol.O, 1, 0)
        game.finishTurn(PlayerSymbol.X, 0, 1)
        game.finishTurn(PlayerSymbol.O, 1, 1)
        game.finishTurn(PlayerSymbol.X, 0, 2)

        val won = game.currentState()
        assertEquals(PlayerSymbol.X, won.winningPlayer)
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), won.score)

        withTimeout(2_000) {
            while (game.currentState().field[0][0] != null) delay(10)
        }
        val restarted = game.currentState()
        assertNull(restarted.winningPlayer)
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), restarted.score)

        game.finishTurn(PlayerSymbol.X, 0, 0)
        game.finishTurn(PlayerSymbol.O, 1, 0)
        game.finishTurn(PlayerSymbol.X, 0, 1)
        game.finishTurn(PlayerSymbol.O, 1, 1)
        game.finishTurn(PlayerSymbol.X, 0, 2)

        assertEquals(mapOf(PlayerSymbol.X to 2, PlayerSymbol.O to 0), game.currentState().score)
        game.shutdown()
    }
}
