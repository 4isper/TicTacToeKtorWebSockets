package com.m4isper.tictactoe.game

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameSnapshotTest {

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
    fun `a snapshot restores the running position score and turn`() {
        val game = TicTacToeGame()
        val x = FakeSession()
        val o = FakeSession()
        game.connectPlayer(x)
        game.connectPlayer(o)
        game.finishTurn(PlayerSymbol.X, x, 0, 0)
        game.finishTurn(PlayerSymbol.O, o, 1, 0)
        game.finishTurn(PlayerSymbol.X, x, 0, 1)
        val snapshot = game.snapshot()
        game.shutdown()

        val restored = TicTacToeGame()
        restored.restoreFrom(snapshot)
        val state = restored.currentState()
        assertEquals(PlayerSymbol.O, state.playerAtTurn)
        assertEquals(PlayerSymbol.X, state.field[0][0])
        assertEquals(PlayerSymbol.O, state.field[0][1])
        assertEquals(PlayerSymbol.X, state.field[1][0])
        assertTrue(state.connectionPlayers.isEmpty()) // live connections are not restored
        assertEquals(snapshot.clockX, restored.snapshot().clockX) // the remaining clocks come back untouched
        assertEquals(snapshot.clockO, restored.snapshot().clockO)

        val x2 = FakeSession()
        val o2 = FakeSession()
        restored.connectPlayer(x2)
        restored.connectPlayer(o2)
        assertNull(restored.finishTurn(PlayerSymbol.O, o2, 1, 1)) // the rejoining pair continues the round
        restored.shutdown()
    }

    @Test
    fun `a restored round-over state continues as the next fresh round keeping the score`() {
        val game = TicTacToeGame(roundRestartDelayMs = 50_000) // no auto-reset before the snapshot
        val x = FakeSession()
        val o = FakeSession()
        game.connectPlayer(x)
        game.connectPlayer(o)
        game.finishTurn(PlayerSymbol.X, x, 0, 0)
        game.finishTurn(PlayerSymbol.O, o, 1, 0)
        game.finishTurn(PlayerSymbol.X, x, 0, 1)
        game.finishTurn(PlayerSymbol.O, o, 1, 1)
        game.finishTurn(PlayerSymbol.X, x, 0, 2)
        assertEquals(PlayerSymbol.X, game.currentState().winningPlayer)
        val snapshot = game.snapshot()
        game.shutdown()

        val restored = TicTacToeGame()
        restored.restoreFrom(snapshot)
        val state = restored.currentState()
        assertNull(state.winningPlayer) // the ended round resumes as a fresh one
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), state.score)
        assertEquals(PlayerSymbol.O, state.playerAtTurn) // the opening alternates
        assertTrue(state.field.all { row -> row.all { it == null } })
        restored.shutdown()
    }
}
