package com.m4isper.tictactoe.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CreateFormTest {

    @Test
    fun `a win line longer than the board is refused`() {
        assertEquals("Линия не может быть длиннее доски", createFormError(3, 4))
        assertEquals("Win line cannot exceed the board", createFormError(3, 4, Lang.EN))
    }

    @Test
    fun `a fitting win line is accepted`() {
        assertNull(createFormError(5, 4))
        assertNull(createFormError(3, 3))
    }

    @Test
    fun `blank fields fall back to the server defaults`() {
        assertNull(createFormError(null, 4))
        assertNull(createFormError(3, null))
        assertNull(createFormError(null, null))
    }
}
