package com.m4isper.tictactoe.domain

object GameRules {
    const val BOARD_SIZE = 3

    fun emptyField(): List<List<PlayerSymbol?>> = List(BOARD_SIZE) { List(BOARD_SIZE) { null } }

    fun isBoardFull(field: List<List<PlayerSymbol?>>): Boolean =
        field.all { row -> row.all { it != null } }

    fun winningPlayer(field: List<List<PlayerSymbol?>>): PlayerSymbol? {
        val lines = field +
                (0 until BOARD_SIZE).map { col -> field.map { it[col] } } +
                listOf(
                    (0 until BOARD_SIZE).map { field[it][it] },
                    (0 until BOARD_SIZE).map { field[it][BOARD_SIZE - 1 - it] }
                )
        return lines.firstOrNull { line -> line[0] != null && line.all { it == line[0] } }?.first()
    }
}
