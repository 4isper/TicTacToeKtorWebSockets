package com.m4isper.tictactoe.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class LangTest {
    @Test
    fun `an explicit parameter still parses the old way`() {
        assertEquals(Lang.EN, parseLang("en"))
        assertEquals(Lang.EN, parseLang("EN"))
        assertEquals(Lang.RU, parseLang("ru"))
        assertEquals(Lang.RU, parseLang(null))
    }

    @Test
    fun `the first recognized accept-language tag wins`() {
        assertEquals(Lang.EN, detectLang("en-US,en;q=0.9"))
        assertEquals(Lang.RU, detectLang("ru-RU,ru;q=0.9,en;q=0.8"))
        assertEquals(Lang.EN, detectLang("en-US,ru;q=0.9")) // order beats the q-values we do not parse
    }

    @Test
    fun `unrecognized tags are skipped and russian stays the default`() {
        assertEquals(Lang.EN, detectLang("de-DE,en;q=0.9"))
        assertEquals(Lang.RU, detectLang("de-DE,fr;q=0.9"))
        assertEquals(Lang.RU, detectLang(null))
        assertEquals(Lang.RU, detectLang(""))
        assertEquals(Lang.EN, detectLang("EN-us"))
    }
}
