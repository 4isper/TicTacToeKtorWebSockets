package com.m4isper.tictactoe.domain

import kotlin.math.abs
import kotlin.random.Random

/**
 * Picks a move for the bot: immediate wins and blocks first, then a depth-limited
 * negamax with alpha-beta over cells adjacent to the played stones. Deterministic:
 * ties resolve toward the board center. The easy level takes an immediate win and
 * otherwise moves at random; hard searches deeper than normal.
 */
object BotBrain {
    private const val WIN_SCORE = 1_000_000

    fun chooseMove(
        field: List<List<PlayerSymbol?>>,
        me: PlayerSymbol,
        winLength: Int,
        level: BotLevel = BotLevel.NORMAL,
        random: Random = Random.Default,
    ): Cell? {
        val size = field.size
        val candidates = candidateCells(field)
        if (candidates.isEmpty()) return null
        val opponent = me.other

        candidates.firstOrNull { completes(field, it, me, winLength) }?.let { return it }
        if (level == BotLevel.EASY) return candidates[random.nextInt(candidates.size)] // easy never looks further
        candidates.firstOrNull { completes(field, it, opponent, winLength) }?.let { return it }

        val depth = when {
            size <= 3 -> size * size
            size <= 5 -> if (level == BotLevel.HARD) 5 else 3
            else -> if (level == BotLevel.HARD) 3 else 2
        }
        var best = candidates.first()
        var bestScore = Int.MIN_VALUE + 1
        var alpha = Int.MIN_VALUE + 1
        for (cell in candidates) {
            val score = -negamax(
                field = applyMove(field, cell, me),
                toMove = opponent,
                root = me,
                winLength = winLength,
                depth = depth - 1,
                alphaIn = -BETA_CEILING,
                betaIn = -alpha,
            )
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
            if (score > alpha) alpha = score
        }
        return best
    }

    private const val BETA_CEILING = Int.MAX_VALUE

    private fun negamax(
        field: List<List<PlayerSymbol?>>,
        toMove: PlayerSymbol,
        root: PlayerSymbol,
        winLength: Int,
        depth: Int,
        alphaIn: Int,
        betaIn: Int,
    ): Int {
        var alpha = alphaIn
        val beta = betaIn
        val win = GameRules.win(field, winLength)
        if (win != null) return if (win.player == toMove) WIN_SCORE else -WIN_SCORE
        if (depth == 0 || GameRules.isBoardFull(field)) return evaluate(field, root, winLength)

        for (cell in candidateCells(field)) {
            val score = -negamax(
                field = applyMove(field, cell, toMove),
                toMove = toMove.other,
                root = root,
                winLength = winLength,
                depth = depth - 1,
                alphaIn = -beta,
                betaIn = -alpha,
            )
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun evaluate(
        field: List<List<PlayerSymbol?>>,
        root: PlayerSymbol,
        winLength: Int,
    ): Int {
        var score = 0
        for (window in windows(field, winLength)) {
            var mine = 0
            var theirs = 0
            for (cell in window) {
                when (field[cell.y][cell.x]) {
                    root -> mine++
                    root.other -> theirs++
                    else -> Unit // empty cell: the window is still open for both sides
                }
            }
            if (mine > 0 && theirs > 0) continue
            score += weight(mine) - weight(theirs)
        }
        return score
    }

    private fun weight(count: Int): Int = if (count <= 0) 0 else 1 shl (count * 2)

    private fun windows(
        field: List<List<PlayerSymbol?>>,
        winLength: Int,
    ): List<List<Cell>> {
        val size = field.size
        val result = mutableListOf<List<Cell>>()
        for (y in 0 until size) {
            for (start in 0..size - winLength) {
                result.add((start until start + winLength).map { Cell(it, y) })
            }
        }
        for (x in 0 until size) {
            for (start in 0..size - winLength) {
                result.add((start until start + winLength).map { Cell(x, it) })
            }
        }
        for (d in 0..size - winLength) {
            result.add((0 until winLength).map { Cell(d + it, d + it) })
            result.add((0 until winLength).map { Cell(size - 1 - d - it, d + it) })
        }
        return result
    }

    private fun candidateCells(field: List<List<PlayerSymbol?>>): List<Cell> {
        val size = field.size
        val occupied = mutableListOf<Cell>()
        field.forEachIndexed { y, row ->
            row.forEachIndexed { x, symbol -> if (symbol != null) occupied.add(Cell(x, y)) }
        }
        if (occupied.isEmpty()) return listOf(Cell(size / 2, size / 2))

        val candidates = linkedSetOf<Cell>()
        for (cell in occupied) {
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val x = cell.x + dx
                    val y = cell.y + dy
                    if (x in 0 until size && y in 0 until size && field[y][x] == null) {
                        candidates.add(Cell(x, y))
                    }
                }
            }
        }
        return candidates.sortedWith(
            compareBy(
                { abs(it.x - size / 2) + abs(it.y - size / 2) },
                { it.y },
                { it.x },
            ),
        )
    }

    private fun applyMove(
        field: List<List<PlayerSymbol?>>,
        cell: Cell,
        player: PlayerSymbol,
    ): List<List<PlayerSymbol?>> = field.mapIndexed { y, row ->
        if (y == cell.y) {
            row.mapIndexed { x, symbol -> if (x == cell.x) player else symbol }
        } else {
            row
        }
    }

    private fun completes(
        field: List<List<PlayerSymbol?>>,
        cell: Cell,
        player: PlayerSymbol,
        winLength: Int,
    ): Boolean = GameRules.win(applyMove(field, cell, player), winLength)?.player == player
}
