package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayShareTest {

    @Test
    fun `a replay round-trips through the fragment`() {
        val fragment = replayToFragment(
            SharedReplay(5, 4, listOf(Cell(0, 0), Cell(4, 4), Cell(2, 2)), "Аня", "Боря"),
        )
        assertTrue(fragment.startsWith("r="))
        assertTrue('=' !in fragment.removePrefix("r=")) // padding is absent: URL-safe as-is

        val back = replayFromFragment(fragment)!!
        assertEquals(5, back.boardSize)
        assertEquals(4, back.winLength)
        assertEquals(listOf(Cell(0, 0), Cell(4, 4), Cell(2, 2)), back.moves)
        assertEquals("Аня", back.xName)
        assertEquals("Боря", back.oName)
    }

    @Test
    fun `padded input still decodes`() {
        val fragment = replayToFragment(SharedReplay(3, 3, listOf(Cell(1, 1))))
        replayFromFragment("$fragment=")!! // a paste that re-added padding
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertNull(replayFromFragment(null))
        assertNull(replayFromFragment(""))
        assertNull(replayFromFragment("other=1"))
        assertNull(replayFromFragment("r="))
        assertNull(replayFromFragment("r=%%%not-base64%%%"))
        assertNull(replayFromFragment("r=eyJzIjoxfQ")) // valid base64, not a replay
    }
}
