package com.m4isper.tictactoe.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Standard Elo: a win over an equal opponent is worth K/2, over a stronger one more,
 * over a weaker one less; a draw moves nothing between equals.
 */
object Elo {
    const val DEFAULT = 1000
    const val K = 32

    /**
     * The step for a player with [ratedGames] rated rounds behind them: a newcomer
     * swings wide (40) so the early climb is quick, then the step cools down to
     * keep an established rating stable.
     */
    fun kFor(ratedGames: Int): Int = when {
        ratedGames < 10 -> 40
        ratedGames < 20 -> 24
        else -> 16
    }

    /** The score a player is expected to take from a game against the given opponent: 1.0 win … 0.0 loss. */
    fun expected(mine: Int, opponent: Int): Double = 1.0 / (1.0 + 10.0.pow((opponent - mine) / 400.0))

    /** The rating after a game worth [score] points (1.0 win, 0.5 draw, 0.0 loss). */
    fun next(mine: Int, opponent: Int, score: Double, k: Int = K): Int = (mine + k * (score - expected(mine, opponent))).roundToInt()
}
