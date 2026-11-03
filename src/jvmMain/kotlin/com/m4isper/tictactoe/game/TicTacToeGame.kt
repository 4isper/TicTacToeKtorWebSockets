package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.BotBrain
import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TicTacToeGame(
    private val roundRestartDelayMs: Long = 5_000L,
    val boardSize: Int = GameRules.DEFAULT_BOARD_SIZE,
    val winLength: Int = boardSize,
    val botLevel: BotLevel? = null,
    val turnTimeoutMs: Long = 30_000L,
    private val onRoundOver: (playerNames: Map<PlayerSymbol, String>, winner: PlayerSymbol?) -> Unit = { _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private val state = MutableStateFlow(GameState(field = GameRules.emptyField(boardSize)))
    private val stateChanges = Channel<GameState>(Channel.UNLIMITED)
    private val lock = Any()

    private val playerSockets = ConcurrentHashMap<PlayerSymbol, WebSocketSession>()
    private val spectatorSockets = ConcurrentHashMap.newKeySet<WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roundRestartJob: Job? = null
    private var roundRestartDeadline = 0L
    private var bot: PlayerSymbol? = null
    private var botMoveJob: Job? = null
    private var turnTimeoutJob: Job? = null
    private var turnDeadline = 0L
    private var armedTurnFor: PlayerSymbol? = null

    private companion object {
        const val BOT_THINK_MS = 400L
    }

    init {
        gameScope.launch {
            for (change in stateChanges) broadcast(change)
        }
    }

    fun connectPlayer(session: WebSocketSession): PlayerSymbol? = synchronized(lock) {
        val current = state.value
        val player = if (PlayerSymbol.X in current.connectionPlayers) PlayerSymbol.O else PlayerSymbol.X
        if (player in current.connectionPlayers) {
            if (bot != player) return null
            detachBotLocked() // a human takes the bot's slot
        }
        playerSockets[player] = session
        publish(state.value.copy(connectionPlayers = state.value.connectionPlayers + player))
        maybeAttachBotLocked()
        player
    }

    fun detachSession(player: PlayerSymbol, session: WebSocketSession): Boolean = synchronized(lock) {
        if (playerSockets[player] === session) {
            playerSockets.remove(player)
            true
        } else {
            false
        }
    }

    fun reattachSession(player: PlayerSymbol, session: WebSocketSession): Unit = synchronized(lock) {
        playerSockets[player] = session
        if (player !in state.value.connectionPlayers) {
            publish(state.value.copy(connectionPlayers = state.value.connectionPlayers + player))
        }
    }

    fun disconnectPlayer(player: PlayerSymbol): Unit = synchronized(lock) {
        playerSockets.remove(player)
        publish(
            state.value.copy(
                connectionPlayers = state.value.connectionPlayers - player,
                playerNames = state.value.playerNames - player,
            ),
        )
        maybeAttachBotLocked()
    }

    fun setName(player: PlayerSymbol, name: String): ErrorReason? = synchronized(lock) {
        val takenByOpponent = state.value.playerNames.any { (other, taken) ->
            other != player && taken.equals(name, ignoreCase = true)
        }
        if (takenByOpponent) {
            ErrorReason.NAME_TAKEN
        } else {
            publish(state.value.copy(playerNames = state.value.playerNames + (player to name)))
            null
        }
    }

    fun attachSpectator(session: WebSocketSession) {
        spectatorSockets.add(session)
    }

    fun detachSpectator(session: WebSocketSession) {
        spectatorSockets.remove(session)
    }

    fun spectatorCount(): Int = spectatorSockets.size

    suspend fun broadcast(state: GameState) {
        for ((player, socket) in playerSockets) {
            try {
                socket.send(
                    protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(state)),
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a player socket", e)
                synchronized(lock) {
                    if (playerSockets[player] === socket) disconnectPlayer(player)
                }
            }
        }
        for (socket in spectatorSockets) {
            try {
                socket.send(
                    protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(state)),
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a spectator socket", e)
                spectatorSockets.remove(socket)
            }
        }
    }

    fun finishTurn(
        player: PlayerSymbol,
        session: WebSocketSession,
        x: Int,
        y: Int,
    ): ErrorReason? {
        synchronized(lock) {
            val current = state.value
            val rejection = rejectionReason(player, session, x, y, current)
            if (rejection != null) return rejection

            val newField = current.field.mapIndexed { row, cells ->
                cells.mapIndexed { col, cell -> if (row == y && col == x) player else cell }
            }
            val isBoardFull = GameRules.isBoardFull(newField)
            val win = GameRules.win(newField, winLength)
            val newScore = if (win != null) {
                current.score + (win.player to (current.score[win.player] ?: 0) + 1)
            } else {
                current.score
            }

            if (win != null || isBoardFull) {
                roundRestartDeadline = System.currentTimeMillis() + roundRestartDelayMs
            }

            publish(
                current.copy(
                    playerAtTurn = player.other,
                    field = newField,
                    isBoardFull = isBoardFull,
                    winningPlayer = win?.player,
                    winningCells = win?.cells ?: emptyList(),
                    score = newScore,
                ),
            )
            if (win != null || isBoardFull) {
                scheduleRoundRestart()
            }
            return null
        }
    }

    fun requestRematch(player: PlayerSymbol, session: WebSocketSession): ErrorReason? {
        synchronized(lock) {
            val current = state.value
            if (playerSockets[player] !== session) return ErrorReason.STALE_SESSION
            if (!current.isRoundOver) return ErrorReason.ROUND_NOT_OVER

            val updated = if (player in current.rematchPlayers) current.rematchPlayers else current.rematchPlayers + player
            val humans = current.connectionPlayers.filter { it != bot } // the bot never asks
            if (updated.isNotEmpty() && humans.all { it in updated }) {
                roundRestartJob?.cancel()
                roundRestartJob = null
                roundRestartDeadline = 0
                publish(
                    current.copy(
                        playerAtTurn = PlayerSymbol.X,
                        field = GameRules.emptyField(boardSize),
                        winningPlayer = null,
                        winningCells = emptyList(),
                        isBoardFull = false,
                        isDrawAgreed = false,
                        roundRestartInMs = null,
                        rematchPlayers = emptyList(),
                    ),
                )
            } else {
                publish(current.copy(rematchPlayers = updated))
            }
            return null
        }
    }

    /** A draw by agreement: the second connected human asking ends the round; asking again withdraws the offer. */
    fun requestDraw(player: PlayerSymbol, session: WebSocketSession): ErrorReason? {
        synchronized(lock) {
            val current = state.value
            if (playerSockets[player] !== session) return ErrorReason.STALE_SESSION
            if (current.isRoundOver) return ErrorReason.ROUND_IS_OVER
            val humans = current.connectionPlayers.filter { it != bot } // the bot never asks
            if (humans.size < 2) return ErrorReason.NOT_YOUR_TURN // nothing to agree with

            val updated = if (player in current.drawPlayers) current.drawPlayers - player else current.drawPlayers + player
            if (updated.size < humans.size) {
                publish(current.copy(drawPlayers = updated))
                return null
            }

            // the agreement completes here: one publish, like finishTurn, so the wire never shows both offers
            roundRestartDeadline = System.currentTimeMillis() + roundRestartDelayMs
            scheduleRoundRestart()
            publish(
                current.copy(
                    isDrawAgreed = true,
                    drawPlayers = emptyList(),
                    rematchPlayers = emptyList(),
                ),
            )
            return null
        }
    }

    fun shutdown() {
        gameScope.cancel()
    }

    /** True when an anonymous joiner with these settings can be paired into this game. */
    fun matches(boardSize: Int, winLength: Int, botLevel: BotLevel?, turnSeconds: Int): Boolean = this.boardSize == boardSize &&
        this.winLength == winLength && this.botLevel == botLevel && this.turnTimeoutMs == turnSeconds * 1000L

    fun isFull(): Boolean = state.value.connectionPlayers.size >= 2

    fun isEmpty(): Boolean = state.value.connectionPlayers.isEmpty()

    fun currentState(): GameState = state.value

    private fun rejectionReason(
        player: PlayerSymbol,
        session: WebSocketSession,
        x: Int,
        y: Int,
        current: GameState,
    ): ErrorReason? {
        val size = current.field.size
        return when {
            playerSockets[player] !== session -> ErrorReason.STALE_SESSION
            x !in 0 until size || y !in 0 until size -> ErrorReason.CELL_OUT_OF_BOUNDS
            current.playerAtTurn != player -> ErrorReason.NOT_YOUR_TURN
            current.isRoundOver -> ErrorReason.ROUND_IS_OVER
            current.field[y][x] != null -> ErrorReason.CELL_OCCUPIED
            else -> null
        }
    }

    private fun scheduleRoundRestart() {
        roundRestartJob?.cancel()
        roundRestartJob = gameScope.launch {
            delay(roundRestartDelayMs)
            synchronized(lock) {
                roundRestartJob = null
                roundRestartDeadline = 0
                publish(
                    state.value.copy(
                        playerAtTurn = PlayerSymbol.X,
                        field = GameRules.emptyField(boardSize),
                        winningPlayer = null,
                        winningCells = emptyList(),
                        isBoardFull = false,
                        isDrawAgreed = false,
                        drawPlayers = emptyList(),
                        roundRestartInMs = null,
                        rematchPlayers = emptyList(),
                    ),
                )
            }
        }
    }

    private fun publish(next: GameState) {
        val previous = state.value
        val withRound = if (roundRestartDeadline > 0) {
            next.copy(roundRestartInMs = (roundRestartDeadline - System.currentTimeMillis()).coerceAtLeast(0))
        } else {
            next
        }
        // arm or disarm first so the wire state never carries a stale turn countdown
        armTurnTimerLocked(withRound)
        val withTurn = if (turnDeadline > 0) {
            withRound.copy(turnRemainingMs = (turnDeadline - System.currentTimeMillis()).coerceAtLeast(0))
        } else {
            withRound.copy(turnRemainingMs = null)
        }
        state.value = withTurn
        stateChanges.trySend(withTurn)
        if (roundJustEnded(previous, withTurn)) {
            onRoundOver(withTurn.playerNames, withTurn.winningPlayer)
        }
        scheduleBotMoveIfNeeded(withTurn)
    }

    private fun roundJustEnded(previous: GameState, next: GameState): Boolean = !previous.isRoundOver && next.isRoundOver

    /** Forfeits the round to the opponent when the current player stalls past the deadline. */
    private fun armTurnTimerLocked(current: GameState) {
        if (current.isRoundOver || current.connectionPlayers.size < 2) {
            turnTimeoutJob?.cancel()
            turnTimeoutJob = null
            turnDeadline = 0
            armedTurnFor = null
            return
        }
        if (current.playerAtTurn == armedTurnFor && turnDeadline > 0) return // same turn: keep the original deadline
        armedTurnFor = current.playerAtTurn
        turnDeadline = System.currentTimeMillis() + turnTimeoutMs
        turnTimeoutJob?.cancel()
        turnTimeoutJob = gameScope.launch {
            delay(turnTimeoutMs)
            synchronized(lock) {
                val snapshot = state.value
                if (snapshot.isRoundOver) return@launch
                val loser = snapshot.playerAtTurn ?: return@launch
                if (snapshot.connectionPlayers.size < 2) return@launch
                if (System.currentTimeMillis() < turnDeadline) return@launch // a newer turn re-armed

                val winner = loser.other
                roundRestartDeadline = System.currentTimeMillis() + roundRestartDelayMs
                turnDeadline = 0 // the round is over — no turn countdown on the wire
                armedTurnFor = null
                scheduleRoundRestart()
                publish(
                    snapshot.copy(
                        winningPlayer = winner,
                        winningCells = emptyList(),
                        score = snapshot.score + (winner to (snapshot.score[winner] ?: 0) + 1),
                        rematchPlayers = emptyList(),
                    ),
                )
            }
        }
    }

    private fun scheduleBotMoveIfNeeded(current: GameState) {
        val symbol = bot ?: return
        val level = botLevel ?: return
        if (current.isRoundOver) return
        if (current.playerAtTurn != symbol) return
        botMoveJob?.cancel()
        botMoveJob = gameScope.launch {
            delay(BOT_THINK_MS)
            synchronized(lock) {
                if (bot != symbol) return@launch
                val snapshot = state.value
                if (snapshot.playerAtTurn != symbol || snapshot.isRoundOver) {
                    return@launch
                }
                val move: Cell = BotBrain.chooseMove(snapshot.field, symbol, winLength, level) ?: return@launch
                finishTurn(symbol, BotSession, move.x, move.y)
            }
        }
    }

    private fun maybeAttachBotLocked() {
        if (botLevel == null || bot != null) return
        if (state.value.connectionPlayers.size != 1) return
        val symbol = if (PlayerSymbol.X in state.value.connectionPlayers) PlayerSymbol.O else PlayerSymbol.X
        playerSockets[symbol] = BotSession
        bot = symbol
        publish(state.value.copy(connectionPlayers = state.value.connectionPlayers + symbol))
    }

    private fun detachBotLocked() {
        val symbol = bot ?: return
        bot = null
        botMoveJob?.cancel()
        botMoveJob = null
        playerSockets.remove(symbol)
        publish(state.value.copy(connectionPlayers = state.value.connectionPlayers - symbol))
    }
}
