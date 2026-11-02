package com.m4isper.tictactoe.models

import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TicTacToeGame(private val roundRestartDelayMs: Long = 5_000L) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private val state = MutableStateFlow(GameState())
    private val stateChanges = Channel<GameState>(Channel.UNLIMITED)
    private val lock = Any()

    private val playerSockets = ConcurrentHashMap<PlayerSymbol, WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roundRestartJob: Job? = null

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

    fun disconnectPlayer(player: PlayerSymbol): Unit = synchronized(lock) {
        playerSockets.remove(player)
        publish(state.value.copy(connectionPlayers = state.value.connectionPlayers - player))
    }

    suspend fun broadcast(state: GameState) {
        playerSockets.values.forEach { socket ->
            try {
                socket.send(
                    Json.encodeToString(state),
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a socket", e)
            }
        }
    }

    fun finishTurn(player: PlayerSymbol, x: Int, y: Int) {
        synchronized(lock) {
            if (!isLegalMove(player, x, y)) return

            val current = state.value
            val newField = current.field.mapIndexed { row, cells ->
                cells.mapIndexed { col, cell -> if (row == y && col == x) player else cell }
            }
            val isBoardFull = GameRules.isBoardFull(newField)
            val winningPlayer = GameRules.winningPlayer(newField)
            val newScore = if (winningPlayer != null) {
                current.score + (winningPlayer to (current.score[winningPlayer] ?: 0) + 1)
            } else {
                current.score
            }

            publish(
                current.copy(
                    playerAtTurn = player.other,
                    field = newField,
                    isBoardFull = isBoardFull,
                    winningPlayer = winningPlayer,
                    score = newScore,
                ),
            )
            if (winningPlayer != null || isBoardFull) scheduleRoundRestart()
        }
    }

    fun shutdown() {
        gameScope.cancel()
    }

    fun isFull(): Boolean = state.value.connectionPlayers.size >= 2

    fun isEmpty(): Boolean = state.value.connectionPlayers.isEmpty()

    fun currentState(): GameState = state.value

    private fun isLegalMove(player: PlayerSymbol, x: Int, y: Int): Boolean {
        val size = GameRules.BOARD_SIZE
        val current = state.value
        return x in 0 until size && y in 0 until size &&
            current.field[y][x] == null &&
            current.winningPlayer == null &&
            current.playerAtTurn == player
    }

    private fun scheduleRoundRestart() {
        roundRestartJob?.cancel()
        roundRestartJob = gameScope.launch {
            delay(roundRestartDelayMs)
            synchronized(lock) {
                publish(
                    state.value.copy(
                        playerAtTurn = PlayerSymbol.X,
                        field = GameState.emptyField(),
                        winningPlayer = null,
                        isBoardFull = false,
                    ),
                )
            }
        }
    }

    private fun publish(next: GameState) {
        state.value = next
        stateChanges.trySend(next)
    }
}
