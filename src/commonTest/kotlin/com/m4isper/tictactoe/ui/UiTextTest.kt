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
        assertEquals("Нет свободных игр — создайте комнату и пригласите друга", errorText(ErrorReason.ROOM_IS_FULL))
        assertEquals("Это имя уже занято", errorText(ErrorReason.NAME_TAKEN))
        assertEquals("Неверное время хода", errorText(ErrorReason.INVALID_TURN_TIME))
        assertEquals("Неверный уровень бота", errorText(ErrorReason.INVALID_BOT_LEVEL))
        assertEquals("Неизвестная реакция", errorText(ErrorReason.INVALID_REACTION))
        assertEquals("Неверное время на партию", errorText(ErrorReason.INVALID_CLOCK))
    }

    @Test
    fun `connection loss is phrased per language`() {
        assertEquals("Соединение потеряно, переподключение…", connectionLostText())
        assertEquals("Connection lost, reconnecting…", connectionLostText(Lang.EN))
        assertEquals("Ошибка соединения", connectionErrorText())
        assertEquals("Connection error", connectionErrorText(Lang.EN))
    }

    @Test
    fun `the opponent-lost plate counts down and then just waits`() {
        assertEquals("Соперник отключился — вернётся за 10 с…", opponentLostText(10))
        assertEquals("Соперник отключился — вернётся за 1 с…", opponentLostText(1))
        assertEquals("Соперник отключился, ждём…", opponentLostText(null))
        assertEquals("The opponent dropped — back in 10s…", opponentLostText(10, Lang.EN))
        assertEquals("The opponent dropped, waiting…", opponentLostText(null, Lang.EN))
    }

    @Test
    fun `draw button follows the offers`() {
        assertEquals("Предложить ничью", drawButtonText(iOffered = false, opponentOffered = false))
        assertEquals("Предложение отправлено…", drawButtonText(iOffered = true, opponentOffered = false))
        assertEquals("Принять ничью", drawButtonText(iOffered = false, opponentOffered = true))
        assertEquals("Offer a draw", drawButtonText(iOffered = false, opponentOffered = false, Lang.EN))
        assertEquals("Accept draw", drawButtonText(iOffered = false, opponentOffered = true, Lang.EN))
    }

    @Test
    fun `an agreed draw is announced`() {
        val drawn = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O), isDrawAgreed = true)
        assertEquals("Ничья по соглашению!", statusText(drawn, PlayerSymbol.X))
        assertEquals("Draw by agreement!", statusText(drawn, PlayerSymbol.X, Lang.EN))
    }

    @Test
    fun `room list is phrased per language`() {
        assertEquals("Открытые комнаты", roomsTitle())
        assertEquals("Open rooms", roomsTitle(Lang.EN))
        assertEquals("Пока нет открытых комнат — создайте свою по ссылке", noRoomsText())
        assertEquals("No open rooms yet — create one with a link", noRoomsText(Lang.EN))
        assertEquals("бот: easy", "${botLabel()}: easy")
        assertEquals("60 с", turnSecondsText(60))
        assertEquals("60s", turnSecondsText(60, Lang.EN))
    }

    @Test
    fun `cells announce their coordinates and contents`() {
        assertEquals("Ряд 2, колонка 3, пусто", cellAriaText(x = 2, y = 1, symbol = null))
        assertEquals("Ряд 1, колонка 1: X", cellAriaText(x = 0, y = 0, symbol = PlayerSymbol.X))
        assertEquals("Ряд 3, колонка 2: O", cellAriaText(x = 1, y = 2, symbol = PlayerSymbol.O))
        assertEquals("Row 2, column 3, empty", cellAriaText(x = 2, y = 1, symbol = null, Lang.EN))
        assertEquals("Row 1, column 1: X", cellAriaText(x = 0, y = 0, symbol = PlayerSymbol.X, Lang.EN))
    }

    @Test
    fun `undo button follows the offers`() {
        assertEquals("Отменить ход", undoButtonText(iOffered = false, opponentOffered = false))
        assertEquals("Ожидание согласия…", undoButtonText(iOffered = true, opponentOffered = false))
        assertEquals("Разрешить отмену", undoButtonText(iOffered = false, opponentOffered = true))
        assertEquals("Undo my move", undoButtonText(iOffered = false, opponentOffered = false, Lang.EN))
        assertEquals("Allow the takeback", undoButtonText(iOffered = false, opponentOffered = true, Lang.EN))
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

    @Test
    fun `language falls back to russian`() {
        assertEquals(Lang.RU, parseLang(null))
        assertEquals(Lang.RU, parseLang("ru"))
        assertEquals(Lang.EN, parseLang("en"))
        assertEquals(Lang.EN, parseLang("EN"))
    }

    @Test
    fun `statuses are available in english`() {
        val won = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            winningPlayer = PlayerSymbol.X,
            playerNames = mapOf(PlayerSymbol.X to "Аня"),
        )
        assertEquals("Аня won!", statusText(won, mySymbol = PlayerSymbol.O, lang = Lang.EN))
        assertEquals(
            "Waiting for the opponent…",
            statusText(GameState(connectionPlayers = listOf(PlayerSymbol.X)), PlayerSymbol.X, Lang.EN),
        )
        assertEquals("Your move", statusText(GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O)), PlayerSymbol.X, Lang.EN))
        assertEquals("Score — X 1 : 2 O", scoreText(GameState(score = mapOf(PlayerSymbol.X to 1, PlayerSymbol.O to 2)), Lang.EN))
        assertEquals("Next round in 5…", restartText(4_500, Lang.EN))
        assertEquals(" · 30s", timerSuffix(29_500, Lang.EN))
        assertEquals("Cell occupied", errorText(ErrorReason.CELL_OCCUPIED, Lang.EN))
        assertEquals("No open games — create a room and invite a friend", errorText(ErrorReason.ROOM_IS_FULL, Lang.EN))
        assertEquals("Name already taken", errorText(ErrorReason.NAME_TAKEN, Lang.EN))
        assertEquals("Invalid turn time", errorText(ErrorReason.INVALID_TURN_TIME, Lang.EN))
        assertEquals("Invalid bot level", errorText(ErrorReason.INVALID_BOT_LEVEL, Lang.EN))
        assertEquals("Unknown reaction", errorText(ErrorReason.INVALID_REACTION, Lang.EN))
        assertEquals("Invalid game clock", errorText(ErrorReason.INVALID_CLOCK, Lang.EN))
        assertEquals("Play again", rematchButtonText(waiting = false, Lang.EN))
        assertEquals("Copy link", copyLinkText(Lang.EN))
    }
}
