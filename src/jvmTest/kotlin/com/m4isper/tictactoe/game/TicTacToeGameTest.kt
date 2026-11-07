package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
class TicTacToeGameTest {

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

    private class DeadSession : FakeSession() {
        override val outgoing: SendChannel<Frame> = Channel<Frame>().apply { close() }
    }

    @Test
    fun `a socket that fails to accept broadcasts is dropped from the game`() = runBlocking {
        val game = TicTacToeGame()
        val oSession = FakeSession()
        game.connectPlayer(DeadSession())
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.O, oSession, 0, 0)

        withTimeout(2_000) {
            while (PlayerSymbol.X in game.currentState().connectionPlayers) delay(10)
        }
        assertEquals(listOf(PlayerSymbol.O), game.currentState().connectionPlayers)

        game.shutdown()
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
        assertEquals(PlayerSymbol.O, restarted.playerAtTurn) // the opening alternates between rounds

        game.finishTurn(PlayerSymbol.O, oSession, 2, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 2, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0) // O must not finish its own column first
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        assertEquals(mapOf(PlayerSymbol.X to 2, PlayerSymbol.O to 0), game.currentState().score)
        game.shutdown()
    }

    @Test
    fun `reaching the series target decides the series and resets the score on the fresh round`() = runBlocking {
        val game = TicTacToeGame(roundRestartDelayMs = 50, seriesTarget = 1)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 0, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 1, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 0)

        val decided = game.currentState()
        assertEquals(PlayerSymbol.X, decided.winningPlayer)
        assertEquals(PlayerSymbol.X, decided.seriesWinner)
        assertEquals(1, decided.seriesTarget)

        withTimeout(2_000) {
            while (game.currentState().isRoundOver) delay(10)
        }
        val fresh = game.currentState()
        assertEquals(0, fresh.score[PlayerSymbol.X])
        assertEquals(0, fresh.score[PlayerSymbol.O])
        assertNull(fresh.seriesWinner)

        game.shutdown()
    }

    @Test
    fun `a win below the series target keeps the series open`() = runBlocking {
        val game = TicTacToeGame(roundRestartDelayMs = 50, seriesTarget = 2)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 0, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 1, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 0)

        val won = game.currentState()
        assertEquals(PlayerSymbol.X, won.winningPlayer)
        assertEquals(1, won.score[PlayerSymbol.X])
        assertNull(won.seriesWinner)

        game.shutdown()
    }

    @Test
    fun `anonymous pairing only matches the same series target`() {
        val game = TicTacToeGame(seriesTarget = 3)
        assertTrue(game.matches(boardSize = 3, winLength = 3, botLevel = null, turnSeconds = 30, seriesTarget = 3))
        assertFalse(game.matches(boardSize = 3, winLength = 3, botLevel = null, turnSeconds = 30, seriesTarget = 0))
        game.shutdown()
    }

    @Test
    fun `anonymous pairing never drops the exhibition flag`() {
        val exhibition = TicTacToeGame(botLevel = BotLevel.NORMAL, secondBotLevel = BotLevel.NORMAL)
        assertTrue(
            exhibition.matches(
                boardSize = 3,
                winLength = 3,
                botLevel = BotLevel.NORMAL,
                turnSeconds = 30,
                secondBotLevel = BotLevel.NORMAL,
            ),
        )
        assertFalse(
            exhibition.matches(
                boardSize = 3,
                winLength = 3,
                botLevel = BotLevel.NORMAL,
                turnSeconds = 30,
                secondBotLevel = null,
            ),
        )
        exhibition.shutdown()
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
        game.setName(PlayerSymbol.X, "Аня")

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
        assertEquals("Аня", reset.playerNames[PlayerSymbol.X]) // names survive the reset

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
    fun `an agreed draw ends the round without a winner`() = runBlocking {
        val reported = Channel<Pair<Map<PlayerSymbol, String>, PlayerSymbol?>>(Channel.UNLIMITED)
        val game = TicTacToeGame(roundRestartDelayMs = 60_000, onRoundOver = { names, winner, _, _ -> reported.trySend(names to winner) })
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)
        game.setName(PlayerSymbol.X, "Аня")

        assertNull(game.requestDraw(PlayerSymbol.X, xSession))
        assertEquals(listOf(PlayerSymbol.X), game.currentState().drawPlayers)

        assertNull(game.requestDraw(PlayerSymbol.O, oSession))
        val agreed = game.currentState()
        assertTrue(agreed.isDrawAgreed)
        assertNull(agreed.winningPlayer)
        assertTrue(agreed.roundRestartInMs != null)
        assertEquals(emptyList(), agreed.drawPlayers)
        assertEquals(ErrorReason.ROUND_IS_OVER, game.finishTurn(PlayerSymbol.X, xSession, 0, 0)) // nothing left to play
        assertNull(game.requestRematch(PlayerSymbol.X, xSession)) // rematch still works
        assertNull(game.requestRematch(PlayerSymbol.O, oSession))
        assertFalse(game.currentState().isDrawAgreed) // the reset cleared it

        val recorded = withTimeout(2_000) { reported.receive() }
        assertEquals(null, recorded.second)
        assertEquals("Аня", recorded.first[PlayerSymbol.X])

        game.shutdown()
    }

    @Test
    fun `a draw offer is withdrawn by asking again`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertNull(game.requestDraw(PlayerSymbol.X, xSession))
        assertEquals(listOf(PlayerSymbol.X), game.currentState().drawPlayers)

        assertNull(game.requestDraw(PlayerSymbol.X, xSession))
        assertEquals(emptyList(), game.currentState().drawPlayers)
        assertFalse(game.currentState().isDrawAgreed)

        game.shutdown()
    }

    @Test
    fun `a draw offer needs a human opponent to agree with`() = runBlocking {
        val game = TicTacToeGame(botLevel = BotLevel.NORMAL)
        val xSession = FakeSession()
        game.connectPlayer(xSession) // the bot takes the other slot
        assertTrue(PlayerSymbol.O in game.currentState().connectionPlayers)

        assertEquals(ErrorReason.NOT_YOUR_TURN, game.requestDraw(PlayerSymbol.X, xSession))

        game.shutdown()
    }

    @Test
    fun `names are published and cleared when the player disconnects`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.setName(PlayerSymbol.X, "Аня")
        assertEquals(mapOf(PlayerSymbol.X to "Аня"), game.currentState().playerNames)

        game.disconnectPlayer(PlayerSymbol.X)
        assertEquals(emptyMap(), game.currentState().playerNames)

        game.shutdown()
    }

    @Test
    fun `a name taken by the opponent is rejected`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertNull(game.setName(PlayerSymbol.X, "Дима"))
        assertEquals(ErrorReason.NAME_TAKEN, game.setName(PlayerSymbol.O, "Дима"))
        assertEquals(ErrorReason.NAME_TAKEN, game.setName(PlayerSymbol.O, "ДИМА")) // case-insensitive
        assertNull(game.setName(PlayerSymbol.O, "Боря"))
        assertNull(game.setName(PlayerSymbol.X, "Дима")) // keeping your own name is fine

        game.shutdown()
    }

    @Test
    fun `an expired turn forfeits the round to the opponent`() = runBlocking {
        val game = TicTacToeGame(turnTimeoutMs = 100, roundRestartDelayMs = 60_000)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        val armed = game.currentState()
        assertTrue(armed.turnRemainingMs != null && armed.turnRemainingMs in 1..100, "turn timer was ${armed.turnRemainingMs}")

        withTimeout(2_000) {
            while (game.currentState().winningPlayer == null) delay(10)
        }
        val forfeited = game.currentState()
        assertEquals(PlayerSymbol.X, forfeited.winningPlayer) // O stalled, X profits
        assertEquals(emptyList(), forfeited.winningCells)
        assertEquals(mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 0), forfeited.score)
        assertTrue(forfeited.roundRestartInMs != null)

        game.shutdown()
    }

    @Test
    fun `the turn timer is not armed while waiting for the opponent`() = runBlocking {
        val game = TicTacToeGame(turnTimeoutMs = 100)
        val xSession = FakeSession()
        game.connectPlayer(xSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        delay(300)

        assertNull(game.currentState().winningPlayer)
        assertNull(game.currentState().turnRemainingMs)

        game.shutdown()
    }

    @Test
    fun `the turn timer disarms when the opponent leaves`() = runBlocking {
        val game = TicTacToeGame(turnTimeoutMs = 1_000)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        assertTrue(game.currentState().turnRemainingMs != null)

        game.disconnectPlayer(PlayerSymbol.O)
        delay(50)
        assertNull(game.currentState().turnRemainingMs) // nobody to forfeit

        game.shutdown()
    }

    @Test
    fun `a move in time cancels the forfeit`() = runBlocking {
        val game = TicTacToeGame(turnTimeoutMs = 1_000)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        delay(300)

        assertNull(game.currentState().winningPlayer)
        assertEquals(PlayerSymbol.X, game.currentState().playerAtTurn)

        game.shutdown()
    }

    @Test
    fun `round end reports the names and the winner`() = runBlocking {
        val reported = Channel<Pair<Map<PlayerSymbol, String>, PlayerSymbol?>>(Channel.UNLIMITED)
        val game = TicTacToeGame(onRoundOver = { names, winner, _, _ -> reported.trySend(names to winner) })
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)
        game.setName(PlayerSymbol.X, "Аня")
        game.setName(PlayerSymbol.O, "Боря")

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        assertEquals(
            mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря") to PlayerSymbol.X,
            withTimeout(2_000) { reported.receive() },
        )

        game.shutdown()
    }

    @Test
    fun `a draw reports a null winner`() = runBlocking {
        val reported = Channel<Pair<Map<PlayerSymbol, String>, PlayerSymbol?>>(Channel.UNLIMITED)
        val game = TicTacToeGame(onRoundOver = { names, winner, _, _ -> reported.trySend(names to winner) })
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)
        game.setName(PlayerSymbol.X, "Аня")

        // the classic draw
        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 2, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 2)
        game.finishTurn(PlayerSymbol.O, oSession, 0, 2)
        game.finishTurn(PlayerSymbol.X, xSession, 1, 2)

        val recorded = withTimeout(2_000) { reported.receive() }
        assertEquals(null, recorded.second)
        assertEquals("Аня", recorded.first[PlayerSymbol.X])

        game.shutdown()
    }

    @Test
    fun `the round reports its moves for the replay`() = runBlocking {
        val reported = Channel<List<Cell>>(Channel.UNLIMITED)
        val game = TicTacToeGame(roundRestartDelayMs = 60_000, onRoundOver = { _, _, moves, _ -> reported.trySend(moves) })
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)

        assertEquals(listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1), Cell(1, 1), Cell(0, 2)), withTimeout(2_000) { reported.receive() })

        // the next round records fresh moves, not the old ones; O opens now
        assertNull(game.requestRematch(PlayerSymbol.X, xSession))
        assertNull(game.requestRematch(PlayerSymbol.O, oSession))
        game.finishTurn(PlayerSymbol.O, oSession, 1, 2)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 2)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 2, 0)
        game.finishTurn(PlayerSymbol.O, oSession, 0, 0) // O must not finish its own column first
        game.finishTurn(PlayerSymbol.X, xSession, 2, 1)

        assertEquals(
            listOf(Cell(1, 2), Cell(2, 2), Cell(1, 0), Cell(2, 0), Cell(0, 0), Cell(2, 1)),
            withTimeout(2_000) { reported.receive() },
        )
        assertNull(withTimeoutOrNull(200) { reported.receive() }, "each round is reported exactly once")

        game.shutdown()
    }

    @Test
    fun `a takeback needs the last mover to ask and the opponent to approve`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        assertNull(game.finishTurn(PlayerSymbol.X, xSession, 0, 0))
        // O is on turn: it cannot take back a move it did not make
        assertEquals(ErrorReason.NOT_YOUR_TURN, game.requestUndo(PlayerSymbol.O, oSession))

        assertNull(game.requestUndo(PlayerSymbol.X, xSession))
        assertEquals(PlayerSymbol.X, game.currentState().undoOfferBy)

        assertNull(game.requestUndo(PlayerSymbol.X, xSession)) // asking again withdraws
        assertNull(game.currentState().undoOfferBy)

        assertNull(game.requestUndo(PlayerSymbol.X, xSession))
        assertNull(game.requestUndo(PlayerSymbol.O, oSession)) // the approval executes it

        val reverted = game.currentState()
        assertNull(reverted.field[0][0])
        assertEquals(PlayerSymbol.X, reverted.playerAtTurn) // the move returns to its maker
        assertNull(reverted.undoOfferBy)
        assertEquals(emptyList(), reverted.rematchPlayers)

        game.shutdown()
    }

    @Test
    fun `a takeback dies when the position moves on and is refused when the round is over`() = runBlocking {
        val game = TicTacToeGame()
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        // X is on turn at the start; O asks to take back — but there is nothing to undo yet
        assertEquals(ErrorReason.NOTHING_TO_UNDO, game.requestUndo(PlayerSymbol.O, oSession))

        assertNull(game.finishTurn(PlayerSymbol.X, xSession, 0, 0))
        assertNull(game.requestUndo(PlayerSymbol.X, xSession))
        assertNull(game.finishTurn(PlayerSymbol.O, oSession, 1, 0))
        assertEquals(null, game.currentState().undoOfferBy) // the offer died with the new move

        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)
        assertEquals(ErrorReason.ROUND_IS_OVER, game.requestUndo(PlayerSymbol.O, oSession))

        game.shutdown()
    }

    @Test
    fun `two bots play an exhibition and a joiner only watches`() = runBlocking {
        val game = TicTacToeGame(botLevel = BotLevel.NORMAL, secondBotLevel = BotLevel.NORMAL)

        assertNull(game.connectPlayer(FakeSession())) // no human slot exists
        assertNull(game.connectPlayer(FakeSession()))
        assertEquals(listOf(PlayerSymbol.X, PlayerSymbol.O), game.currentState().connectionPlayers)

        withTimeout(5_000) {
            while (game.currentState().field.sumOf { row -> row.count { it != null } } < 2) delay(10)
        }
        // one round ends, the exhibition resets and keeps going
        withTimeout(30_000) {
            while (game.currentState().field.sumOf { row -> row.count { it != null } } > 0) delay(50)
        }

        game.shutdown()
    }

    @Test
    fun `the clock ticks the mover down and resets with the round`() = runBlocking {
        val game = TicTacToeGame(clockBudgetMs = 60_000, roundRestartDelayMs = 100)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        delay(150) // X spends a little thinking time
        assertNull(game.finishTurn(PlayerSymbol.X, xSession, 0, 0))

        val afterX = game.currentState()
        assertTrue(afterX.clocks.getValue(PlayerSymbol.X) in 55_000..60_000, "X clock was ${afterX.clocks[PlayerSymbol.X]}")
        assertTrue(afterX.clocks.getValue(PlayerSymbol.O) in 59_900..60_000, "O has not thought yet")

        // the round ends, the clocks come back full
        game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
        game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
        game.finishTurn(PlayerSymbol.X, xSession, 0, 2)
        withTimeout(2_000) {
            while (game.currentState().field[0][0] != null) delay(10)
        }
        val restarted = game.currentState()
        assertTrue(restarted.clocks.getValue(PlayerSymbol.X) in 59_900..60_000, "X clock is full again")
        assertTrue(restarted.clocks.getValue(PlayerSymbol.O) in 59_900..60_000, "O clock is full again")

        game.shutdown()
    }

    @Test
    fun `an exhausted clock forfeits the round`() = runBlocking {
        val game = TicTacToeGame(clockBudgetMs = 150, roundRestartDelayMs = 60_000)
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        withTimeout(2_000) {
            while (game.currentState().winningPlayer == null) delay(10)
        }
        val forfeited = game.currentState()
        assertEquals(PlayerSymbol.O, forfeited.winningPlayer) // X let the clock run out
        assertEquals(mapOf(PlayerSymbol.X to 0, PlayerSymbol.O to 1), forfeited.score)
        assertTrue(forfeited.roundRestartInMs != null)

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

    @Test
    fun `a slow round recorder does not freeze the game`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val game = TicTacToeGame(
            roundRestartDelayMs = 60_000,
            onRoundOver = { _, _, _, _ ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                Unit
            },
        )
        val xSession = FakeSession()
        val oSession = FakeSession()
        game.connectPlayer(xSession)
        game.connectPlayer(oSession)

        val play = async(Dispatchers.IO) {
            game.finishTurn(PlayerSymbol.X, xSession, 0, 0)
            game.finishTurn(PlayerSymbol.O, oSession, 1, 0)
            game.finishTurn(PlayerSymbol.X, xSession, 0, 1)
            game.finishTurn(PlayerSymbol.O, oSession, 1, 1)
            game.finishTurn(PlayerSymbol.X, xSession, 0, 2)
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS), "the round recorder never ran")
        val progressed = async(Dispatchers.IO) {
            game.connectPlayer(FakeSession())
            true
        }
        assertEquals(true, withTimeoutOrNull(1_000) { progressed.await() }, "the game lock was held while recording")
        release.countDown()
        play.await()
        game.shutdown()
    }
}
