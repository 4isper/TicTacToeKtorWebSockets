package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.test.*

class UiTextTest {

    @Test
    fun `win beats every other status`() {
        val state = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            winningPlayer = PlayerSymbol.X,
        )
        assertEquals("Победил X!", statusText(state, mySymbol = PlayerSymbol.O))
    }

    @Test
    fun `full board is a draw`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), isBoardFull = true)
        assertEquals("Ничья!", statusText(state, mySymbol = PlayerSymbol.X))
    }

    @Test
    fun `waiting for the opponent beats my turn`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X), playerAtTurn = PlayerSymbol.X)
        assertEquals("Ожидание соперника…", statusText(state, mySymbol = PlayerSymbol.X))
    }

    @Test
    fun `my turn when both players are connected`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), playerAtTurn = PlayerSymbol.X)
        assertEquals("Ваш ход", statusText(state, mySymbol = PlayerSymbol.X))
    }

    @Test
    fun `opponent turn when both players are connected`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), playerAtTurn = PlayerSymbol.O)
        assertEquals("Ход: O", statusText(state, mySymbol = PlayerSymbol.X))
    }

    @Test
    fun `status uses the player name when it is known`() {
        val state = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerAtTurn = PlayerSymbol.O,
            playerNames = mapOf(PlayerSymbol.O to "Дима"),
        )
        assertEquals("Ход: Дима", statusText(state, mySymbol = PlayerSymbol.X))
    }

    @Test
    fun `the winner is announced by name when it is known`() {
        val state = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            winningPlayer = PlayerSymbol.X,
            playerNames = mapOf(PlayerSymbol.X to "Аня"),
        )
        assertEquals("Победил Аня!", statusText(state, mySymbol = PlayerSymbol.O))
    }

    @Test
    fun `score is rendered for both symbols`() {
        val state = GameState(score = mapOf(PlayerSymbol.X to 2, PlayerSymbol.O to 1))
        assertEquals("Счёт — X 2 : 1 O", scoreText(state))
    }

    @Test
    fun `missing score entries count as zero`() {
        assertEquals("Счёт — X 0 : 0 O", scoreText(GameState(score = emptyMap())))
    }

    @Test
    fun `errors are phrased for the player`() {
        assertEquals("Клетка занята", errorText(ErrorReason.CELL_OCCUPIED))
        assertEquals("Сейчас не ваш ход", errorText(ErrorReason.NOT_YOUR_TURN))
        assertEquals("Сессия не найдена", errorText(ErrorReason.UNKNOWN_TOKEN))
        assertEquals("Эта вкладка больше не в игре", errorText(ErrorReason.STALE_SESSION))
        assertEquals("Неверный размер доски", errorText(ErrorReason.INVALID_BOARD_SIZE))
        assertEquals("Неверная длина победной линии", errorText(ErrorReason.INVALID_WIN_LENGTH))
        assertEquals("Раунд ещё идёт", errorText(ErrorReason.ROUND_NOT_OVER))
    }

    @Test
    fun `restart countdown rounds up so it never shows zero while waiting`() {
        assertEquals("Новый раунд через 5…", restartText(4_500))
        assertEquals("Новый раунд через 1…", restartText(999))
    }

    @Test
    fun `turn timer suffix rounds up`() {
        assertEquals(" · 30 с", timerSuffix(29_500))
        assertEquals(" · 1 с", timerSuffix(50))
    }
}
