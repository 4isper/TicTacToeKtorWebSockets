package com.m4isper.tictactoe.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionLimiterTest {

    @Test
    fun `one address cannot open more than its share`() {
        val limiter = ConnectionLimiter(maxPerIp = 2, maxTotal = 10)

        assertTrue(limiter.acquire("1.2.3.4"))
        assertTrue(limiter.acquire("1.2.3.4"))
        assertFalse(limiter.acquire("1.2.3.4"))

        assertTrue(limiter.acquire("5.6.7.8")) // a different address still gets in
        assertEquals(3, limiter.total())
    }

    @Test
    fun `a released slot can be taken again`() {
        val limiter = ConnectionLimiter(maxPerIp = 1, maxTotal = 10)

        assertTrue(limiter.acquire("a"))
        assertFalse(limiter.acquire("a"))
        limiter.release("a")
        assertTrue(limiter.acquire("a"))
        assertEquals(1, limiter.count("a"))
    }

    @Test
    fun `the total cap stops every address`() {
        val limiter = ConnectionLimiter(maxPerIp = 10, maxTotal = 2)

        assertTrue(limiter.acquire("a"))
        assertTrue(limiter.acquire("b"))
        assertFalse(limiter.acquire("c"))
        limiter.release("a")
        assertTrue(limiter.acquire("c"))
    }

    @Test
    fun `an extra release cannot drive the counter negative`() {
        val limiter = ConnectionLimiter(maxPerIp = 2, maxTotal = 2)

        limiter.release("ghost")
        limiter.acquire("a")
        limiter.release("a")
        limiter.release("a")

        assertEquals(0, limiter.total())
        assertEquals(0, limiter.count("a"))
    }
}
