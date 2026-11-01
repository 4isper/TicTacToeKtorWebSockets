package com.m4isper.tictactoe.models

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

    private val playerSockets = ConcurrentHashMap<Char, WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roundRestartJob: Job? = null

    init {
        state.onEach(::broadcast).launchIn(gameScope)
    }

    fun connectPlayer(session: WebSocketSession): Char? = synchronized(lock) {
        val current = state.value
        val player = if ('X' in current.connectionPlayers) 'O' else 'X'
        if (player in current.connectionPlayers) {
            null
        } else {
            playerSockets[player] = session
            state.value = current.copy(connectionPlayers = current.connectionPlayers + player)
            player
        }
    }

    fun disconnectPlayer(player: Char): Unit = synchronized(lock) {
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

    fun finishTurn(player: Char, x: Int, y: Int) {
        synchronized(lock) {
            if (x !in 0..2 || y !in 0..2) return
            val current = state.value
            if (current.field[y][x] != null || current.winningPlayer != null) return
            if (current.playerAtTurn != player) return

            val newField = current.field.mapIndexed { row, cells ->
                cells.mapIndexed { col, cell -> if (row == y && col == x) player else cell }
            }
            val isBoardFull = newField.all { row -> row.all { it != null } }
            val winningPlayer = getWinningPlayer(newField)

            state.value = current.copy(
                playerAtTurn = if (player == 'X') 'O' else 'X',
                field = newField,
                isBoardFull = isBoardFull,
                winningPlayer = winningPlayer
            )
            if (winningPlayer != null || isBoardFull) scheduleRoundRestart()
        }
    }

    private fun getWinningPlayer(field: List<List<Char?>>): Char? {
        return if (field[0][0] != null && field[0][0] == field[0][1] && field[0][1] == field[0][2]){
            field[0][0]
        } else if (field[1][0] != null && field[1][0] == field[1][1] && field[1][1] == field[1][2]){
            field[1][0]
        } else if (field[2][0] != null && field[2][0] == field[2][1] && field[2][1] == field[2][2]){
            field[2][0]
        } else if (field[0][0] != null && field[0][0] == field[1][0] && field[1][0] == field[2][0]){
            field[0][0]
        } else if (field[0][1] != null && field[0][1] == field[1][1] && field[1][1] == field[2][1]){
            field[0][1]
        } else if (field[0][2] != null && field[0][2] == field[1][2] && field[1][2] == field[2][2]){
            field[0][2]
        } else if (field[0][0] != null && field[0][0] == field[1][1] && field[1][1] == field[2][2]){
            field[0][0]
        } else if (field[0][2] != null && field[0][2] == field[1][1] && field[1][1] == field[2][0]){
            field[0][2]
        } else null
    }

    private fun scheduleRoundRestart() {
        roundRestartJob?.cancel()
        roundRestartJob = gameScope.launch {
            delay(roundRestartDelayMs)
            synchronized(lock) {
                state.value = state.value.copy(
                    playerAtTurn = 'X',
                    field = GameState.emptyField(),
                    winningPlayer = null,
                    isBoardFull = false
                )
            }
        }
    }
}
