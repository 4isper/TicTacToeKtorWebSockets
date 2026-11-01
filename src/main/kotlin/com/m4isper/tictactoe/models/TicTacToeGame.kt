package com.m4isper.tictactoe.models

import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class TicTacToeGame(private val roundRestartDelayMs: Long = 5_000L) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private val state = MutableStateFlow(GameState())
    private val lock = Any()

    private val playerSockets = ConcurrentHashMap<PlayerSymbol, WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roundRestartJob: Job? = null

    init {
        state.onEach(::broadcast).launchIn(gameScope)
    }

    fun connectPlayer(session: WebSocketSession): PlayerSymbol? = synchronized(lock) {
        val current = state.value
        val player = if (PlayerSymbol.X in current.connectionPlayers) PlayerSymbol.O else PlayerSymbol.X
        if (player in current.connectionPlayers) {
            null
        } else {
            playerSockets[player] = session
            state.value = current.copy(connectionPlayers = current.connectionPlayers + player)
            player
        }
    }

    fun disconnectPlayer(player: PlayerSymbol): Unit = synchronized(lock) {
        playerSockets.remove(player)
        state.value = state.value.copy(connectionPlayers = state.value.connectionPlayers - player)
    }

    suspend fun broadcast(state: GameState){
        playerSockets.values.forEach { socket ->
            try {
                socket.send(
                    Json.encodeToString(state)
                )
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a socket", e)
            }
        }
    }

    fun finishTurn(player: PlayerSymbol, x: Int, y: Int) {
        synchronized(lock) {
            val size = GameRules.BOARD_SIZE
            if (x !in 0 until size || y !in 0 until size) return
            val current = state.value
            if (current.field[y][x] != null || current.winningPlayer != null) return
            if (current.playerAtTurn != player) return

            val newField = current.field.mapIndexed { row, cells ->
                cells.mapIndexed { col, cell -> if (row == y && col == x) player else cell }
            }
            val isBoardFull = GameRules.isBoardFull(newField)
            val winningPlayer = GameRules.winningPlayer(newField)

            state.value = current.copy(
                playerAtTurn = player.other,
                field = newField,
                isBoardFull = isBoardFull,
                winningPlayer = winningPlayer
            )
            if (winningPlayer != null || isBoardFull) scheduleRoundRestart()
        }
    }

    private fun scheduleRoundRestart() {
        roundRestartJob?.cancel()
        roundRestartJob = gameScope.launch {
            delay(roundRestartDelayMs)
            synchronized(lock) {
                state.value = state.value.copy(
                    playerAtTurn = PlayerSymbol.X,
                    field = GameState.emptyField(),
                    winningPlayer = null,
                    isBoardFull = false
                )
            }
        }
    }
}
