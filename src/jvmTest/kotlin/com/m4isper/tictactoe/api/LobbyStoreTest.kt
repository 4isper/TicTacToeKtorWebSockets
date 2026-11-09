package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameSnapshot
import com.m4isper.tictactoe.game.GameState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyStoreTest {

    private fun sampleLobby() = PersistedLobby(
        rooms = listOf(
            PersistedRoom(
                name = "alpha",
                boardSize = 3,
                winLength = 3,
                botLevel = BotLevel.HARD,
                turnSeconds = 30,
                clockSeconds = 0,
                seriesTarget = 0,
                snapshot = GameSnapshot(
                    state = GameState(field = GameState.emptyField(3), winLength = 3),
                    roundStarter = PlayerSymbol.X,
                    moves = listOf(Cell(0, 0)),
                    clockX = 1000,
                    clockO = 2000,
                ),
            ),
        ),
        names = mapOf("key-anya" to "аня"),
    )

    @Test
    fun `a saved lobby loads back identically`() {
        val file = Files.createTempFile("lobby-test", ".json").toFile()
        JsonLobbyStore(file).save(sampleLobby())

        val loaded = JsonLobbyStore(file).load()
        assertEquals(sampleLobby(), loaded)
    }

    @Test
    fun `a missing file loads as null`() {
        val file = Files.createTempFile("lobby-test", ".json").toFile()
        file.delete()
        assertNull(JsonLobbyStore(file).load())
    }

    @Test
    fun `a corrupt file loads as null and keeps a backup`() {
        val file = Files.createTempFile("lobby-test", ".json").toFile()
        file.writeText("{ not json")

        assertNull(JsonLobbyStore(file).load())
        val backup = file.resolveSibling("${file.name}.corrupt")
        assertTrue(backup.exists())
        assertEquals("{ not json", backup.readText())
    }
}
