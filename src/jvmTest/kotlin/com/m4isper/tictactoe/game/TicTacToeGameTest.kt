package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.ErrorReason
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
import kotlin.test.assertTrue
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
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertEquals(ErrorReason.CELL_OUT_OF_BOUNDS, game.finishTurn(PlayerSymbol.X, xSession, 3, 0))
        assertEquals(ErrorReason.NOT_YOUR_TURN, game.finishTurn(PlayerSymbol.O, oSession, 0, 0))
        assertNull(game.finishTurn(PlayerSymbol.X, xSession, 0, 0))
        assertEquals(ErrorReason.CELL_OCCUPIED, game.finishTurn(PlayerSymbol.O, oSession, 0, 0))

        game.shutdown()
    }

    @Test
    fun `a session that lost the player slot cannot play for the player`() = runBlocking {
        val game = TicTacToeGame()
        val originalSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(originalSession)
        game.connectPlayer(oSession)

        val replacementSession = FakeSession()
        game.reattachSession(PlayerSymbol.X, replacementSession)

        assertEquals(ErrorReason.STALE_SESSION, game.finishTurn(PlayerSymbol.X, originalSession, 0, 0))
        assertNull(game.finishTurn(PlayerSymbol.X, replacementSession, 0, 0))

        game.shutdown()
    }

    @Test
    fun `score accumulates across rounds and survives the auto restart`() = runBlocking {
        val game = TicTacToeGame(roundRestartDelayMs = 50)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        val won = game.currentState()
        assertEquals(PlayerSymbol.X, won.winningPlayer)
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), won.score)

        withTimeout(2_000) {
            while (game.currentState().field[0][0] != null) delay(10)
        }
        val restarted = game.currentState()
        assertNull(restarted.winningPlayer)
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), restarted.score)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        assertEquals(mapOf(PlayerSymbol.X to 2, PlayerSymbol.O to 0), game.currentState().score)
        game.shutdown()
    }

    @Test
    fun `rematch is rejected while the round is running`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertEquals(ErrorReason.ROUND_NOT_OVER, game.requestRematch(PlayerSymbol.X, xSession))

        game.shutdown()
    }

    @Test
    fun `round end arms the countdown and rematch resets when both ask`() = runBlocking {
        val game = TicTacToeGame(roundRestartDelayMs = 60_000)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        val ended = game.currentState()
        assertEquals(PlayerSymbol.X, ended.winningPlayer)
        val countdown = ended.roundRestartInMs
        assertTrue(countdown != null && countdown in 1..60_000, "countdown was $countdown")

        assertNull(game.requestRematch(PlayerSymbol.X, xSession))
        val afterFirst = game.currentState()
        assertEquals(listOf(PlayerSymbol.X), afterFirst.rematchPlayers)
        assertEquals(PlayerSymbol.X, afterFirst.field[0][0]) // not reset yet

        assertNull(game.requestRematch(PlayerSymbol.O, oSession))
        val reset = game.currentState()
        assertNull(reset.winningPlayer)
        assertNull(reset.roundRestartInMs)
        assertEquals(emptyList(), reset.rematchPlayers)
        assertNull(reset.field[0][0])
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), reset.score)

        game.shutdown()
    }

    @Test
    fun `rematch from a stale session is rejected`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        assertEquals(ErrorReason.STALE_SESSION, game.requestRematch(PlayerSymbol.X, FakeSession()))

        game.shutdown()
    }

    @Test
    fun `four by four board accepts moves up to its bounds and detects the win`() = runBlocking {
        val game = TicTacToeGame(boardSize = 4)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertEquals(ErrorReason.CELL_OUT_OF_BOUNDS, game.finishTurn(PlayerSymbol.X, xSession, 4, 0))

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 2)
        assertNull(game.finishTurn(PlayerSymbol.X, xSession, 0, 3))

        val won = game.currentState()
        assertEquals(PlayerSymbol.X, won.winningPlayer)
        assertEquals(4, won.field.size)
        game.shutdown()
    }
}
