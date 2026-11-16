package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionHeaderTest {

    @Test
    fun `a player learns their symbol and the opponent's`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val header = sessionHeader(state, mySymbol = PlayerSymbol.X, joined = true)
        assertEquals(SessionRole.PLAYER, header.role)
        assertEquals("Вы: X · Соперник: O", header.identity)
        assertEquals("3×3 · линия 3", header.rules)
    }

    @Test
    fun `names are shown next to the symbols`() {
        val state = GameState(
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            playerNames = mapOf(PlayerSymbol.X to "Аня", PlayerSymbol.O to "Боря"),
        )
        val header = sessionHeader(state, mySymbol = PlayerSymbol.O, joined = true)
        assertEquals("Вы: Боря (O) · Соперник: Аня (X)", header.identity)
    }

    @Test
    fun `a spectator is told they are watching`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val header = sessionHeader(state, mySymbol = null, joined = true)
        assertEquals(SessionRole.SPECTATOR, header.role)
        assertEquals("Зритель · вы смотрите партию", header.identity)
        assertEquals("3×3 · линия 3", header.rules)
    }

    @Test
    fun `before joining there is nothing to claim`() {
        val header = sessionHeader(GameState(), mySymbol = null, joined = false)
        assertEquals(SessionRole.CONNECTING, header.role)
        assertEquals("", header.identity)
        assertEquals("", header.rules)
    }

    @Test
    fun `a 5x5 win-4 round announces the rule it plays by`() {
        val state = GameState(field = GameState.emptyField(5), winLength = 4)
        assertEquals("5×5 · линия 4", sessionHeader(state, PlayerSymbol.X, joined = true).rules)
    }

    @Test
    fun `the header reads in english`() {
        val state = GameState(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O))
        val player = sessionHeader(state, PlayerSymbol.X, joined = true, lang = Lang.EN)
        assertEquals("You: X · Opponent: O", player.identity)
        assertEquals("3×3 · win 3", player.rules)
        val watcher = sessionHeader(state, null, joined = true, lang = Lang.EN)
        assertEquals("Spectator · you are watching", watcher.identity)
    }
}
