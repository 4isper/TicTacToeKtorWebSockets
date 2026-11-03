package com.m4isper.tictactoe.domain

object GameRules {
    const val DEFAULT_BOARD_SIZE = 3
    const val MIN_BOARD_SIZE = 3
    const val MAX_BOARD_SIZE = 10

    fun emptyField(size: Int = DEFAULT_BOARD_SIZE): List<List<PlayerSymbol?>> = List(size) { List(size) { null } }

    fun isBoardFull(field: List<List<PlayerSymbol?>>): Boolean = field.all { row -> row.all { it != null } }

    fun winningPlayer(field: List<List<PlayerSymbol?>>): PlayerSymbol? {
        val size = field.size
        val lines =
            field +
                (0 until size).map { col -> field.map { it[col] } } +
                listOf(
                    (0 until size).map { field[it][it] },
                    (0 until size).map { field[it][size - 1 - it] },
                )
        return lines.firstOrNull { line -> line[0] != null && line.all { it == line[0] } }?.first()
    }
}
