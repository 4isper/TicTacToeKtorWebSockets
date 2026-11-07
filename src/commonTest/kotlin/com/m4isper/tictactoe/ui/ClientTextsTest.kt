package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.protocol.REACTION_EMOJIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientTextsTest {

    @Test
    fun `reaction labels are meaningful for every shipped emoji`() {
        REACTION_EMOJIS.forEach { emoji ->
            val ru = reactionLabel(emoji, Lang.RU)
            val en = reactionLabel(emoji, Lang.EN)
            assertTrue(ru.isNotBlank(), "RU label for $emoji")
            assertTrue(en.isNotBlank(), "EN label for $emoji")
            assertTrue(ru != emoji, "RU label must not be the bare emoji")
            assertTrue(en != emoji, "EN label must not be the bare emoji")
            assertTrue(ru != en, "labels must be localized")
        }
    }

    @Test
    fun `reaction labels are localized per emoji`() {
        assertEquals("Нравится", reactionLabel("👍", Lang.RU))
        assertEquals("Сердце", reactionLabel("❤️", Lang.RU))
        assertEquals("Смех", reactionLabel("😂", Lang.RU))
        assertEquals("Удивление", reactionLabel("😮", Lang.RU))
        assertEquals("Thumbs up", reactionLabel("👍", Lang.EN))
        assertEquals("Heart", reactionLabel("❤️", Lang.EN))
        assertEquals("Laughing", reactionLabel("😂", Lang.EN))
        assertEquals("Surprised", reactionLabel("😮", Lang.EN))
    }

    @Test
    fun `an unknown emoji falls back to a generic label`() {
        assertEquals("Реакция", reactionLabel("🦄", Lang.RU))
        assertEquals("Reaction", reactionLabel("🦄", Lang.EN))
    }

    @Test
    fun `retry delay ramps up and is capped at five seconds`() {
        assertEquals(1_000L, retryDelayMs(1))
        assertEquals(2_000L, retryDelayMs(2))
        assertEquals(5_000L, retryDelayMs(5))
        assertEquals(5_000L, retryDelayMs(10))
        assertEquals(5_000L, retryDelayMs(100))
        assertEquals(1_000L, retryDelayMs(0))
        assertEquals(1_000L, retryDelayMs(-3))
    }

    @Test
    fun `automatic retries are capped`() {
        assertEquals(10, MAX_AUTO_RETRIES)
    }

    @Test
    fun `connection status texts are localized`() {
        assertEquals("Подключение…", connectingText())
        assertEquals("Connecting…", connectingText(Lang.EN))
        assertEquals("Переподключение…", reconnectingText())
        assertEquals("Reconnecting…", reconnectingText(Lang.EN))
        assertEquals("Повторить", retryNowText())
        assertEquals("Retry now", retryNowText(Lang.EN))
    }

    @Test
    fun `pwa notice and install texts are localized`() {
        assertEquals("Доступно обновление — перезагрузите страницу", updateNoticeText())
        assertEquals("Update available — reload", updateNoticeText(Lang.EN))
        assertEquals("Перезагрузить", reloadText())
        assertEquals("Reload", reloadText(Lang.EN))
        assertEquals("Скрыть", dismissText())
        assertEquals("Dismiss", dismissText(Lang.EN))
        assertEquals("Установить приложение", installText())
        assertEquals("Install app", installText(Lang.EN))
    }

    @Test
    fun `copy failure and the OK button are localized`() {
        assertEquals("Не удалось скопировать", copyFailedText())
        assertEquals("Copy failed", copyFailedText(Lang.EN))
        assertEquals("ОК", okText())
        assertEquals("OK", okText(Lang.EN))
    }

    @Test
    fun `theme color follows the chosen palette`() {
        assertEquals("#f4f4f8", themeColor(ThemeMode.AUTO, prefersLight = true))
        assertEquals("#14141f", themeColor(ThemeMode.AUTO, prefersLight = false))
        assertEquals("#f4f4f8", themeColor(ThemeMode.LIGHT, prefersLight = true))
        assertEquals("#f4f4f8", themeColor(ThemeMode.LIGHT, prefersLight = false))
        assertEquals("#14141f", themeColor(ThemeMode.DARK, prefersLight = true))
        assertEquals("#14141f", themeColor(ThemeMode.DARK, prefersLight = false))
    }
}
