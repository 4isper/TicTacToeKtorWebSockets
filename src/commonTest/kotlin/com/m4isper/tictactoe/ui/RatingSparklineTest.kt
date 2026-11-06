package com.m4isper.tictactoe.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatingSparklineTest {

    @Test
    fun `too few points draw nothing`() {
        assertEquals(emptyList(), ratingSparklinePoints(emptyList()))
        assertEquals(emptyList(), ratingSparklinePoints(listOf(1020)))
    }

    @Test
    fun `the oldest point sits left and the newest right`() {
        val points = ratingSparklinePoints(listOf(1000, 1020, 1040))

        assertTrue(points[0].first < points[1].first && points[1].first < points[2].first, "$points")
        assertEquals(0.0, points.first().first)
        assertEquals(100.0, points.last().first)
    }

    @Test
    fun `a rise lifts the newest point to the top`() {
        val points = ratingSparklinePoints(listOf(1000, 1040))

        assertEquals(30.0, points[0].second) // the low rating sits at the bottom
        assertEquals(0.0, points[1].second) // the high one at the top
    }

    @Test
    fun `a flat rating draws a flat line`() {
        val points = ratingSparklinePoints(listOf(1000, 1000, 1000))

        assertTrue(points.all { it.second == points[0].second }, "$points")
    }
}
