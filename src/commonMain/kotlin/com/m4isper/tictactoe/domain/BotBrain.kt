package com.m4isper.tictactoe.domain

import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Picks a move for the bot: immediate wins and blocks first, then a depth-limited
 * negamax with alpha-beta over cells adjacent to the played stones. Deterministic:
 * ties resolve toward the board center. The easy level takes an immediate win and
 * otherwise moves at random; normal searches at a fixed depth, hard keeps deepening
 * while its time budget lasts and plays the best move of the deepest finished pass.
 */
object BotBrain {
    private const val WIN_SCORE = 1_000_000

    /** How long hard may search; an iteration that outlives the budget is discarded, not played. */
    private val HARD_SEARCH_BUDGET = 900.milliseconds

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

        if (level == BotLevel.NORMAL) {
            return bestAtDepth(field, candidates, me, winLength, normalDepth(size), search = null).cell
        }
        return deepBest(field, candidates, me, winLength)
    }

    /** The fixed depth normal plays at: perfect on 3×3, shallower on bigger boards. */
    private fun normalDepth(size: Int): Int = when {
        size <= 3 -> size * size
        size <= 5 -> 3
        else -> 2
    }

    /** Hard: deepen from 2 plies upward until the budget runs out or the search proves a win. */
    private fun deepBest(
        field: List<List<PlayerSymbol?>>,
        candidates: List<Cell>,
        me: PlayerSymbol,
        winLength: Int,
    ): Cell {
        val deadline = TimeSource.Monotonic.markNow() + HARD_SEARCH_BUDGET
        var best = bestAtDepth(field, candidates, me, winLength, depth = 2, search = null)
        var nextDepth = 3
        var proven = false
        while (nextDepth <= field.size * field.size && !proven) {
            val search = Search(deadline)
            val deeper = bestAtDepth(field, candidates, me, winLength, nextDepth, search)
            if (search.aborted) break // a partial pass proves nothing: keep the previous depth's move
            best = deeper
            proven = best.score >= WIN_SCORE // a proven win needs no deeper look
            nextDepth++
        }
        return best.cell
    }

    private const val BETA_CEILING = Int.MAX_VALUE

    private data class RankedMove(val cell: Cell, val score: Int)

    /** The live budget of one deep pass; an aborted pass is discarded by its caller. */
    private class Search(val deadline: TimeSource.Monotonic.ValueTimeMark) {
        var aborted = false
    }

    /** The best move at an exact depth, ties resolving toward the board center like the candidates are ordered. */
    private fun bestAtDepth(
        field: List<List<PlayerSymbol?>>,
        candidates: List<Cell>,
        me: PlayerSymbol,
        winLength: Int,
        depth: Int,
        search: Search?,
    ): RankedMove {
        var best = candidates.first()
        var bestScore = Int.MIN_VALUE + 1
        var alpha = Int.MIN_VALUE + 1
        for (cell in candidates) {
            val score = -negamax(
                field = applyMove(field, cell, me),
                toMove = me.other,
                root = me,
                winLength = winLength,
                depth = depth - 1,
                alphaIn = -BETA_CEILING,
                betaIn = -alpha,
                search = search,
            )
            if (search?.aborted == true) return RankedMove(best, bestScore) // throwaway: the caller keeps the last full pass
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
            if (score > alpha) alpha = score
        }
        return RankedMove(best, bestScore)
    }

    private fun negamax(
        field: List<List<PlayerSymbol?>>,
        toMove: PlayerSymbol,
        root: PlayerSymbol,
        winLength: Int,
        depth: Int,
        alphaIn: Int,
        betaIn: Int,
        search: Search?,
    ): Int {
        // out of budget, or a sibling subtree already killed this pass: unwind without wasting time
        if (search != null && (search.aborted || TimeSource.Monotonic.markNow() > search.deadline)) {
            search.aborted = true
            return 0
        }
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
                search = search,
            )
            if (search?.aborted == true) return 0
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
        for (window in GameRules.windows(field.size, winLength)) {
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
