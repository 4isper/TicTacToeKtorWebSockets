package com.m4isper.tictactoe.domain

import kotlin.test.*

class GameRulesTest {

    @Test
    fun `row of same symbols wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { _, _ -> if (row == 1) PlayerSymbol.O else null }
        }
        val win = GameRules.win(field)
        assertEquals(PlayerSymbol.O, win?.player)
        assertEquals(listOf(Cell(0, 1), Cell(1, 1), Cell(2, 1)), win?.cells)
    }

    @Test
    fun `column of same symbols wins`() {
        val field = GameRules.emptyField().mapIndexed { _, cells ->
            cells.mapIndexed { col, _ -> if (col == 2) PlayerSymbol.X else null }
        }
        val win = GameRules.win(field)
        assertEquals(PlayerSymbol.X, win?.player)
        assertEquals(listOf(Cell(2, 0), Cell(2, 1), Cell(2, 2)), win?.cells)
    }

    @Test
    fun `main diagonal wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == col) PlayerSymbol.X else null }
        }
        val win = GameRules.win(field)
        assertEquals(PlayerSymbol.X, win?.player)
        assertEquals(listOf(Cell(0, 0), Cell(1, 1), Cell(2, 2)), win?.cells)
    }

    @Test
    fun `anti diagonal wins`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row + col == 2) PlayerSymbol.O else null }
        }
        val win = GameRules.win(field)
        assertEquals(PlayerSymbol.O, win?.player)
        assertEquals(listOf(Cell(2, 0), Cell(1, 1), Cell(0, 2)), win?.cells)
    }

    @Test
    fun `four by four diagonal wins`() {
        val field = GameRules.emptyField(size = 4).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (row == col) PlayerSymbol.X else null }
        }
        assertEquals(PlayerSymbol.X, GameRules.win(field, winLength = 4)?.player)
    }

    @Test
    fun `four in a row wins on a five by five`() {
        val field = GameRules.emptyField(size = 5).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col == 0 && row <= 3) PlayerSymbol.X else null }
        }
        val win = GameRules.win(field, winLength = 4)
        assertEquals(PlayerSymbol.X, win?.player)
        assertEquals(listOf(Cell(0, 0), Cell(0, 1), Cell(0, 2), Cell(0, 3)), win?.cells)
    }

    @Test
    fun `an offset diagonal of four wins on a five by five`() {
        val field = GameRules.emptyField(size = 5).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col - row == 1 && row <= 3) PlayerSymbol.X else null }
        }
        val win = GameRules.win(field, winLength = 4)
        assertEquals(PlayerSymbol.X, win?.player)
        assertEquals(listOf(Cell(1, 0), Cell(2, 1), Cell(3, 2), Cell(4, 3)), win?.cells)
    }

    @Test
    fun `an offset anti diagonal of four wins on a five by five`() {
        val field = GameRules.emptyField(size = 5).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col + row == 3 && row <= 3) PlayerSymbol.O else null }
        }
        val win = GameRules.win(field, winLength = 4)
        assertEquals(PlayerSymbol.O, win?.player)
        assertEquals(listOf(Cell(3, 0), Cell(2, 1), Cell(1, 2), Cell(0, 3)), win?.cells)
    }

    @Test
    fun `a short offset diagonal of three wins on a five by five`() {
        val field = GameRules.emptyField(size = 5).mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col - row == 2 && row <= 2) PlayerSymbol.X else null }
        }
        val win = GameRules.win(field, winLength = 3)
        assertEquals(PlayerSymbol.X, win?.player)
        assertEquals(listOf(Cell(2, 0), Cell(3, 1), Cell(4, 2)), win?.cells)
    }

    @Test
    fun `windows are unique and exactly winLength long`() {
        listOf(3 to 3, 5 to 3, 5 to 4, 10 to 5).forEach { (size, winLength) ->
            val windows = GameRules.windows(size, winLength)
            assertTrue(windows.isNotEmpty(), "no windows for $size/$winLength")
            assertTrue(windows.all { it.size == winLength }, "wrong window size for $size/$winLength")
            assertEquals(windows.size, windows.toSet().size, "duplicate windows for $size/$winLength")
        }
    }

    @Test
    fun `a shorter run does not win`() {
        val field = GameRules.emptyField().mapIndexed { row, cells ->
            cells.mapIndexed { col, _ -> if (col == 0 && row <= 1) PlayerSymbol.O else null }
        }
        assertNull(GameRules.win(field, winLength = 3))
    }

    @Test
    fun `the first run of the required length wins`() {
        val field = listOf(
            listOf<PlayerSymbol?>(PlayerSymbol.X, PlayerSymbol.X, PlayerSymbol.X),
            listOf(PlayerSymbol.O, PlayerSymbol.O, null),
            listOf(null, null, null),
        )
        assertEquals(PlayerSymbol.X, GameRules.win(field, winLength = 3)?.player)
    }

    @Test
    fun `win length longer than any line cannot win`() {
        val field = listOf(
            listOf<PlayerSymbol?>(PlayerSymbol.X, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.O, PlayerSymbol.X, PlayerSymbol.O),
            listOf(PlayerSymbol.X, PlayerSymbol.O, PlayerSymbol.X),
        )
        assertNull(GameRules.win(field, winLength = 4))
    }

    @Test
    fun `draw has no winner but the board is full`() {
        val field = listOf(
            listOf(PlayerSymbol.X, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.O, PlayerSymbol.O, PlayerSymbol.X),
            listOf(PlayerSymbol.X, PlayerSymbol.X, PlayerSymbol.O),
        )
        assertNull(GameRules.win(field))
        assertTrue(GameRules.isBoardFull(field))
    }

    @Test
    fun `empty field has no winner and is not full`() {
        val field = GameRules.emptyField()
        assertNull(GameRules.win(field))
        assertFalse(GameRules.isBoardFull(field))
    }
}
