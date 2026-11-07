package com.m4isper.tictactoe.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginTest {

    @Test
    fun `a browser from the same host is allowed`() {
        assertTrue(isOriginAllowed("https://example.com", "example.com", "https"))
        assertTrue(isOriginAllowed("http://localhost:8080", "localhost:8080", "http"))
    }

    @Test
    fun `a foreign origin is refused`() {
        assertFalse(isOriginAllowed("https://evil.example", "example.com", "https"))
        assertFalse(isOriginAllowed("http://example.com", "example.com", "https")) // scheme downgrade
        assertFalse(isOriginAllowed("null", "example.com", "https")) // a sandboxed iframe
    }

    @Test
    fun `a non browser client without an origin stays allowed`() {
        assertTrue(isOriginAllowed(null, "example.com", "https"))
        assertTrue(isOriginAllowed("", "example.com", "https"))
    }

    @Test
    fun `explicitly configured origins and the wildcard work behind a proxy`() {
        assertTrue(isOriginAllowed("https://play.example", "internal:8080", "http", listOf("https://play.example")))
        assertTrue(isOriginAllowed("https://anything.example", "example.com", "https", listOf("*")))
    }
}
