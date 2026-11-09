package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
    fun `a hostile board size is refused before any field is built`() {
        assertNull(replayFromFragment(replayToFragment(SharedReplay(100_000, 3, emptyList()))))
        assertNull(replayFromFragment(replayToFragment(SharedReplay(2, 3, emptyList()))))
    }

    @Test
    fun `an impossible win length is refused`() {
        assertNull(replayFromFragment(replayToFragment(SharedReplay(5, 2, emptyList()))))
        assertNull(replayFromFragment(replayToFragment(SharedReplay(5, 6, emptyList()))))
    }

    @Test
    fun `moves outside the board and beyond its capacity are refused`() {
        assertNull(replayFromFragment(replayToFragment(SharedReplay(3, 3, listOf(Cell(9, 9))))))
        assertNull(replayFromFragment(replayToFragment(SharedReplay(3, 3, listOf(Cell(-1, 0))))))
        assertNull(replayFromFragment(replayToFragment(SharedReplay(3, 3, List(101) { Cell(0, 0) }))))
    }

    @Test
    fun `oversized names and fragments are refused`() {
        assertNull(replayFromFragment(replayToFragment(SharedReplay(3, 3, emptyList(), xName = "x".repeat(21)))))
        assertNull(replayFromFragment("r=" + "A".repeat(5_000)))
    }

    @Test
    fun `a move that is not a coordinate pair is refused`() {
        assertNull(replayFromFragment(rawFragment("""{"s":3,"w":3,"m":[[0]]}""")))
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

    @OptIn(ExperimentalEncodingApi::class)
    private fun rawFragment(json: String): String = "r=" + Base64.UrlSafe
        .withPadding(Base64.PaddingOption.ABSENT)
        .encode(json.encodeToByteArray())
}
