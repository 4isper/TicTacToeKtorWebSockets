package com.m4isper.tictactoe.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatTextTest {

    @Test
    fun `blank text is refused`() {
        assertNull(normalizeChatText(""))
        assertNull(normalizeChatText("   \n  "))
    }

    @Test
    fun `text is trimmed and line breaks are flattened`() {
        assertEquals("привет мир", normalizeChatText("  привет\nмир  "))
    }

    @Test
    fun `overlong text is refused while the limit itself is kept`() {
        assertNull(normalizeChatText("x".repeat(MAX_CHAT_LENGTH + 1)))
        assertEquals("x".repeat(MAX_CHAT_LENGTH), normalizeChatText("x".repeat(MAX_CHAT_LENGTH)))
    }
}
