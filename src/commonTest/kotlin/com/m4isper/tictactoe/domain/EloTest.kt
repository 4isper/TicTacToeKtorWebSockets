package com.m4isper.tictactoe.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EloTest {

    @Test
    fun `equal opponents trade sixteen points for a win`() {
        assertEquals(1016, Elo.next(1000, 1000, 1.0))
        assertEquals(984, Elo.next(1000, 1000, 0.0))
        assertEquals(1000, Elo.next(1000, 1000, 0.5)) // a draw between equals moves nothing
    }

    @Test
    fun `an upset pays more than a expected win`() {
        val underdogGain = Elo.next(1000, 1200, 1.0) - 1000
        val favoriteGain = Elo.next(1200, 1000, 1.0) - 1200
        assertTrue(underdogGain > favoriteGain, "underdog +$underdogGain vs favorite +$favoriteGain")
    }

    @Test
    fun `the exchange is symmetric modulo rounding`() {
        val gain = Elo.next(1000, 1200, 1.0) - 1000
        val loss = 1200 - Elo.next(1200, 1000, 0.0)
        assertTrue(kotlin.math.abs(gain - loss) <= 1, "gain $gain vs loss $loss")
    }

    @Test
    fun `the k factor cools down as rated games accumulate`() {
        assertEquals(40, Elo.kFor(0)) // a newcomer swings wide
        assertEquals(40, Elo.kFor(9))
        assertEquals(24, Elo.kFor(10))
        assertEquals(24, Elo.kFor(19))
        assertEquals(16, Elo.kFor(20)) // a veteran settles down
    }

    @Test
    fun `the k factor scales the step`() {
        assertEquals(1020, Elo.next(1000, 1000, 1.0, k = 40))
        assertEquals(1016, Elo.next(1000, 1000, 1.0, k = 32))
    }
}
