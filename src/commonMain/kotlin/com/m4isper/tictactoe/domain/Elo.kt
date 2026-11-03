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

    /** The score a player is expected to take from a game against the given opponent: 1.0 win … 0.0 loss. */
    fun expected(mine: Int, opponent: Int): Double = 1.0 / (1.0 + 10.0.pow((opponent - mine) / 400.0))

    /** The rating after a game worth [score] points (1.0 win, 0.5 draw, 0.0 loss). */
    fun next(mine: Int, opponent: Int, score: Double): Int = (mine + K * (score - expected(mine, opponent))).roundToInt()
}
