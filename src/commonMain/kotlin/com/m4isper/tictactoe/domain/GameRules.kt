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
    const val MAX_SERIES_WINS = 10

    fun emptyField(size: Int = DEFAULT_BOARD_SIZE): List<List<PlayerSymbol?>> = List(size) { List(size) { null } }

    fun isBoardFull(field: List<List<PlayerSymbol?>>): Boolean = field.all { row -> row.all { it != null } }

    /** Every segment of exactly winLength cells: rows, columns and all diagonals in both directions. */
    fun windows(size: Int, winLength: Int): List<List<Cell>> {
        if (winLength !in 1..size) return emptyList()
        val lines = mutableListOf<List<Cell>>()
        for (y in 0 until size) lines.add((0 until size).map { x -> Cell(x, y) })
        for (x in 0 until size) lines.add((0 until size).map { y -> Cell(x, y) })
        for (startX in 0 until size) lines.add(diagonal(size, startX, 0, 1))
        for (startY in 1 until size) lines.add(diagonal(size, 0, startY, 1))
        for (startX in 0 until size) lines.add(diagonal(size, startX, 0, -1))
        for (startY in 1 until size) lines.add(diagonal(size, size - 1, startY, -1))
        return lines.filter { it.size >= winLength }.flatMap { line ->
            (0..line.size - winLength).map { start -> line.subList(start, start + winLength) }
        }
    }

    private fun diagonal(size: Int, startX: Int, startY: Int, dx: Int): List<Cell> {
        val cells = mutableListOf<Cell>()
        var x = startX
        var y = startY
        while (x in 0 until size && y in 0 until size) {
            cells.add(Cell(x, y))
            x += dx
            y++
        }
        return cells
    }

    fun win(
        field: List<List<PlayerSymbol?>>,
        winLength: Int = DEFAULT_BOARD_SIZE,
    ): Win? {
        for (window in windows(field.size, winLength)) {
            val player = field[window.first().y][window.first().x] ?: continue
            if (window.all { field[it.y][it.x] == player }) {
                return Win(player = player, cells = window)
            }
        }
        return null
    }
}
