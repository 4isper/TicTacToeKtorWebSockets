package com.m4isper.tictactoe.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class JoinParamsTest {

    @Test
    fun `known settings ride the query in a stable order`() {
        val query = joinQuery(
            mapOf(
                "room" to "alpha",
                "size" to "5",
                "win" to "4",
                "bot" to "hard",
                "turn" to "60",
                "clock" to "300",
                "lang" to "en",
                "junk" to "x",
            ),
        )
        assertEquals("room=alpha&size=5&win=4&bot=hard&turn=60&clock=300&lang=en", query)
    }

    @Test
    fun `blank values are dropped so GET-form defaults survive`() {
        val query = joinQuery(
            mapOf(
                "room" to "",
                "size" to "3",
                "win" to "",
                "bot" to "",
                "turn" to "30",
                "clock" to "",
                "lang" to null,
            ),
        )
        assertEquals("size=3&turn=30", query)
    }

    @Test
    fun `values are percent encoded so a name cannot break the query`() {
        val query = joinQuery(mapOf("room" to "a b", "name" to "Аня&Б", "lang" to "en"))
        assertEquals("room=a%20b&name=%D0%90%D0%BD%D1%8F%26%D0%91&lang=en", query)
        assertEquals("%F0%9F%98%80", urlEncode("😀"))
        assertEquals("alpha-5_1.2~3", urlEncode("alpha-5_1.2~3"))
    }

    @Test
    fun `the player key rides the query next to the name`() {
        val query = joinQuery(mapOf("name" to "Аня", "key" to "abc-123"))
        assertEquals("name=%D0%90%D0%BD%D1%8F&key=abc-123", query)
    }

    @Test
    fun `nothing to send builds no query`() {
        assertEquals("", joinQuery(emptyMap()))
    }
}
