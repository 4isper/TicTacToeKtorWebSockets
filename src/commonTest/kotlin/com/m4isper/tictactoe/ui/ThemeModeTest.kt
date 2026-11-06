package com.m4isper.tictactoe.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun `the stored value parses into a mode, everything else follows the system`() {
        assertEquals(ThemeMode.LIGHT, parseTheme("light"))
        assertEquals(ThemeMode.DARK, parseTheme("dark"))
        assertEquals(ThemeMode.AUTO, parseTheme(null))
        assertEquals(ThemeMode.AUTO, parseTheme("auto"))
        assertEquals(ThemeMode.AUTO, parseTheme("LIGHT"))
        assertEquals(ThemeMode.AUTO, parseTheme("banana"))
    }

    @Test
    fun `the toggle hints describe the next mode`() {
        assertEquals("Тема: как в системе — включить светлую", themeTitle(ThemeMode.AUTO))
        assertEquals("Тема: светлая — включить тёмную", themeTitle(ThemeMode.LIGHT))
        assertEquals("Тема: тёмная — вернуть как в системе", themeTitle(ThemeMode.DARK))
        assertEquals("Theme: light — switch to dark", themeTitle(ThemeMode.LIGHT, Lang.EN))
    }

    @Test
    fun `the icon marks the mode`() {
        assertEquals("🌓", themeIcon(ThemeMode.AUTO))
        assertEquals("☀️", themeIcon(ThemeMode.LIGHT))
        assertEquals("🌙", themeIcon(ThemeMode.DARK))
    }
}
