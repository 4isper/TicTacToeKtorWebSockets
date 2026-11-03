package com.m4isper.tictactoe.game

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
    private val boardSize: Int = GameRules.DEFAULT_BOARD_SIZE,
    private val winLength: Int = boardSize,
) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private val state = MutableStateFlow(GameState(field = GameRules.emptyField(boardSize)))
    private val stateChanges = Channel<GameState>(Channel.UNLIMITED)
    private val lock = Any()

    private val playerSockets = ConcurrentHashMap<PlayerSymbol, WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roundRestartJob: Job? = null
    private var roundRestartDeadline = 0L

    init {
        gameScope.launch {
            for (change in stateChanges) broadcast(change)
        }
    }

    fun connectPlayer(session: WebSocketSession): PlayerSymbol? = synchronized(lock) {
        val current = state.value
        val player = if (PlayerSymbol.X in current.connectionPlayers) PlayerSymbol.O else PlayerSymbol.X
        if (player in current.connectionPlayers) {
            null
        } else {
            playerSockets[player] = session
            publish(current.copy(connectionPlayers = current.connectionPlayers + player))
            player
        }
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
        publish(state.value.copy(connectionPlayers = state.value.connectionPlayers - player))
    }

    suspend fun broadcast(state: GameState) {
        playerSockets.values.forEach { socket ->
            try {
                socket.send(
                    protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(state)),
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a socket", e)
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
            if (current.winningPlayer == null && !current.isBoardFull) return ErrorReason.ROUND_NOT_OVER

            val updated = if (player in current.rematchPlayers) current.rematchPlayers else current.rematchPlayers + player
            if (updated.isNotEmpty() && current.connectionPlayers.all { it in updated }) {
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

    fun shutdown() {
        gameScope.cancel()
    }

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
            current.winningPlayer != null -> ErrorReason.ROUND_IS_OVER
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
                        roundRestartInMs = null,
                        rematchPlayers = emptyList(),
                    ),
                )
            }
        }
    }

    private fun publish(next: GameState) {
        val withCountdown = if (roundRestartDeadline > 0) {
            next.copy(roundRestartInMs = (roundRestartDeadline - System.currentTimeMillis()).coerceAtLeast(0))
        } else {
            next
        }
        state.value = withCountdown
        stateChanges.trySend(withCountdown)
    }
}
