package com.m4isper.tictactoe.domain

object GameRules {
    const val DEFAULT_BOARD_SIZE = 3
    const val MIN_BOARD_SIZE = 3
    const val MAX_BOARD_SIZE = 10
    const val MIN_WIN_LENGTH = 3
    const val DEFAULT_TURN_SECONDS = 30
    const val MIN_TURN_SECONDS = 5
    const val MAX_TURN_SECONDS = 600
    const val MIN_CLOCK_SECONDS = 30
    const val MAX_CLOCK_SECONDS = 3600

    fun emptyField(size: Int = DEFAULT_BOARD_SIZE): List<List<PlayerSymbol?>> = List(size) { List(size) { null } }

    fun isBoardFull(field: List<List<PlayerSymbol?>>): Boolean = field.all { row -> row.all { it != null } }

    fun win(
        field: List<List<PlayerSymbol?>>,
        winLength: Int = DEFAULT_BOARD_SIZE,
    ): Win? {
        if (winLength < 1) return null
        val size = field.size
        val lines: List<List<Pair<Cell, PlayerSymbol?>>> =
            field.mapIndexed { y, row -> row.mapIndexed { x, symbol -> Cell(x, y) to symbol } } +
                (0 until size).map { x -> field.mapIndexed { y, row -> Cell(x, y) to row[x] } } +
                listOf(
                    (0 until size).map { d -> Cell(d, d) to field[d][d] },
                    (0 until size).map { d -> Cell(size - 1 - d, d) to field[d][size - 1 - d] },
                )
        for (line in lines) {
            for (start in 0..line.size - winLength) {
                val window = line.subList(start, start + winLength)
                val player = window.first().second ?: continue
                if (window.all { it.second == player }) {
                    return Win(player = player, cells = window.map { it.first })
                }
            }
        }
        return null
    }
}
