package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameLobbyTest {

    @Suppress("EmptyFunctionBlock")
    private open class FakeSession : WebSocketSession {
        override val coroutineContext = SupervisorJob()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun flush() {}
        override fun terminate() {}
    }

    @Test
    fun `anonymous matching pairs only identical settings`() {
        val lobby = GameLobby()
        val first = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4)))
        val otherSize = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 3)))
        val otherWin = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5)))
        val otherTurn = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4, turnSeconds = 60)))
        assertNotSame(first.game, otherSize.game) // their settings would otherwise be silently ignored
        assertNotSame(first.game, otherWin.game)
        assertNotSame(first.game, otherTurn.game)

        val partner = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4)))
        assertSame(first.game, partner.game)
        assertEquals(PlayerSymbol.O, partner.symbol) // paired into the matching game

        val third = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4)))
        assertNotSame(first.game, third.game) // the full pair stays closed

        lobby.shutdownAll()
    }

    @Test
    fun `room snapshots list live named rooms with their settings`() {
        val lobby = GameLobby()
        lobby.join(FakeSession(), JoinSettings(room = "alpha", size = 5, winLength = 4, botLevel = BotLevel.HARD, turnSeconds = 60))
        lobby.join(FakeSession(), JoinSettings(room = "alpha")) // the second player
        lobby.join(FakeSession(), JoinSettings(room = "alpha")) // a spectator
        lobby.join(FakeSession(), JoinSettings(room = "beta"))
        lobby.join(FakeSession(), JoinSettings()) // anonymous: never listed

        val rooms = lobby.roomSnapshots()
        assertEquals(listOf("alpha", "beta"), rooms.map { it.name })
        val alpha = rooms.first()
        assertEquals(2, alpha.players)
        assertEquals(1, alpha.spectators)
        assertEquals(5, alpha.boardSize)
        assertEquals(4, alpha.winLength)
        assertEquals(BotLevel.HARD, alpha.botLevel)
        assertEquals(60, alpha.turnSeconds)

        lobby.shutdownAll()
    }

    @Test
    fun `an emptied room disappears from the snapshots`() = runBlocking {
        val lobby = GameLobby(reconnectGraceMs = 50)
        val session = FakeSession()
        val first = assertNotNull(lobby.join(session, JoinSettings(room = "alpha")))

        lobby.sessionClosed(first.token, session)
        delay(150)

        assertTrue(lobby.roomSnapshots().isEmpty())
        lobby.shutdownAll()
    }

    @Test
    fun `the lobby refuses to grow past its game limit`() {
        val lobby = GameLobby(maxGames = 1)
        assertNotNull(lobby.join(FakeSession(), JoinSettings()))
        assertNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4))) // a new game would exceed the cap
        lobby.shutdownAll()
    }

    @Test
    fun `a room admits only a bounded number of spectators`() {
        val lobby = GameLobby(maxSpectatorsPerGame = 1)
        lobby.join(FakeSession(), JoinSettings(room = "alpha"))
        lobby.join(FakeSession(), JoinSettings(room = "alpha"))
        assertNotNull(lobby.join(FakeSession(), JoinSettings(room = "alpha"))) // first spectator
        assertNull(lobby.join(FakeSession(), JoinSettings(room = "alpha"))) // second one is refused
        lobby.shutdownAll()
    }

    @Test
    fun `a name cannot be claimed by two live tokens`() {
        val lobby = GameLobby()
        val first = assertNotNull(lobby.join(FakeSession(), JoinSettings()))
        val second = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 5, winLength = 4)))
        assertTrue(lobby.claimName(first.token, "Аня"))
        assertFalse(lobby.claimName(second.token, "аня")) // case-insensitive ownership
        assertTrue(lobby.claimName(first.token, "Аня")) // the owner keeps its own name
        lobby.releaseName(first.token)
        assertTrue(lobby.claimName(second.token, "Аня")) // freed on disconnect
        lobby.shutdownAll()
    }

    @Test
    fun `bot games never pair with each other`() {
        val lobby = GameLobby()
        val first = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 3, botLevel = BotLevel.NORMAL)))
        val easy = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 3, botLevel = BotLevel.EASY)))
        assertNotSame(first.game, easy.game) // a different level is a different game
        val second = assertNotNull(lobby.join(FakeSession(), JoinSettings(size = 3, botLevel = BotLevel.NORMAL)))
        assertNotSame(first.game, second.game)
        assertEquals(2, first.game.currentState().connectionPlayers.size) // the bot fills the second slot

        lobby.shutdownAll()
    }

    @Test
    fun `a spectate join watches an empty room instead of taking a slot`() {
        val lobby = GameLobby()
        val spectator = assertNotNull(lobby.join(FakeSession(), JoinSettings(room = "alpha", spectate = true)))
        assertNull(spectator.symbol) // a watcher, not the X player
        assertEquals(emptyList(), spectator.game.currentState().connectionPlayers)

        val player = assertNotNull(lobby.join(FakeSession(), JoinSettings(room = "alpha")))
        assertEquals(PlayerSymbol.X, player.symbol) // the first player slot was free for them

        lobby.shutdownAll()
    }

    @Test
    fun `a spectator does not hold the room it opened`() {
        val lobby = GameLobby()
        val session = FakeSession()
        val spectator = assertNotNull(lobby.join(session, JoinSettings(room = "alpha", spectate = true)))

        lobby.sessionClosed(spectator.token, session)

        val player = assertNotNull(lobby.join(FakeSession(), JoinSettings(room = "alpha")))
        assertNotSame(spectator.game, player.game) // the abandoned room was retired, not kept alive

        lobby.shutdownAll()
    }

    @Test
    fun `a spectate flag without a room is ignored`() {
        val lobby = GameLobby()
        val joined = assertNotNull(lobby.join(FakeSession(), JoinSettings(spectate = true)))
        assertEquals(PlayerSymbol.X, joined.symbol) // anonymous matchmaking still plays

        lobby.shutdownAll()
    }

    @Test
    fun `anonymous pairing prefers the closest rated player`() = runBlocking {
        val ratings = mapOf("Аня" to 1200, "Боря" to 900, "Вера" to 1000, "Гоша" to 1000)
        val lobby = GameLobby(reconnectGraceMs = 50, ratingOf = { ratings[it] })

        val (_, gosha) = twoWaitingRatedGames(lobby, ratings)

        val joiner = assertNotNull(lobby.join(FakeSession(), JoinSettings(rating = 1000)))
        assertSame(gosha.game, joiner.game) // 1000-rated Гоша over 900-rated Боря

        lobby.shutdownAll()
    }

    @Test
    fun `an unnamed joiner keeps the first come pairing`() = runBlocking {
        val ratings = mapOf("Аня" to 1200, "Боря" to 900, "Вера" to 1000, "Гоша" to 1000)
        val lobby = GameLobby(reconnectGraceMs = 50, ratingOf = { ratings[it] })

        val (boria, _) = twoWaitingRatedGames(lobby, ratings)

        val joiner = assertNotNull(lobby.join(FakeSession(), JoinSettings()))
        assertSame(boria.game, joiner.game) // no rating to match by: the oldest game wins

        lobby.shutdownAll()
    }

    @Test
    fun `a rated joiner prefers a rated waiting game over an anonymous one`() = runBlocking {
        val lobby = GameLobby(reconnectGraceMs = 50, ratingOf = { name -> mapOf("Аня" to 1200, "Боря" to 900)[name] })

        // game 1: Аня (1200) + Боря (900); game 2: two anonymous players
        val sa = FakeSession()
        val a = assertNotNull(lobby.join(sa, JoinSettings(rating = 1200)))
        val b = assertNotNull(lobby.join(FakeSession(), JoinSettings(rating = 900)))
        assertSame(a.game, b.game)
        assertTrue(lobby.claimName(a.token, "Аня"))
        a.game.setName(a.symbol!!, "Аня")
        assertTrue(lobby.claimName(b.token, "Боря"))
        b.game.setName(b.symbol!!, "Боря")

        val sc = FakeSession()
        val c = assertNotNull(lobby.join(sc, JoinSettings()))
        assertNotNull(lobby.join(FakeSession(), JoinSettings())) // pairs into game 2

        lobby.sessionClosed(a.token, sa)
        lobby.sessionClosed(c.token, sc)
        delay(150) // both graces expire: Боря (rated) and an anonymous player wait

        val joiner = assertNotNull(lobby.join(FakeSession(), JoinSettings(rating = 1200)))
        assertSame(a.game, joiner.game) // the rated waiting game beats the anonymous one

        lobby.shutdownAll()
    }

    /** Two full same-settings games, one rated player of each dropped out; their games wait with Боря and Гоша. */
    private suspend fun twoWaitingRatedGames(
        lobby: GameLobby,
        ratings: Map<String, Int>,
    ): Pair<GameLobby.Joined, GameLobby.Joined> {
        fun name(joined: GameLobby.Joined, it: String) {
            assertTrue(lobby.claimName(joined.token, it))
            joined.game.setName(joined.symbol!!, it)
        }
        val sa = FakeSession()
        val sc = FakeSession()
        val a = assertNotNull(lobby.join(sa, JoinSettings(rating = ratings.getValue("Аня"))))
        val b = assertNotNull(lobby.join(FakeSession(), JoinSettings(rating = ratings.getValue("Боря"))))
        assertSame(a.game, b.game) // paired
        name(a, "Аня")
        name(b, "Боря")
        val c = assertNotNull(lobby.join(sc, JoinSettings(rating = ratings.getValue("Вера"))))
        val d = assertNotNull(lobby.join(FakeSession(), JoinSettings(rating = ratings.getValue("Гоша"))))
        assertSame(c.game, d.game) // a full pair leaves no waiting game: a fresh one starts
        name(c, "Вера")
        name(d, "Гоша")

        lobby.sessionClosed(a.token, sa)
        lobby.sessionClosed(c.token, sc)
        delay(150) // both graces expire

        return b to d
    }
}
