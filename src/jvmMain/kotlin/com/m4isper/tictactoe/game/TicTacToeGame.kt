package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.BotBrain
import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.protocol.REACTION_EMOJIS
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
    val secondBotLevel: BotLevel? = null,
    val turnTimeoutMs: Long = 30_000L,
    val clockBudgetMs: Long? = null,
    private val onRoundOver: (playerNames: Map<PlayerSymbol, String>, winner: PlayerSymbol?, moves: List<Cell>, boardSize: Int) -> Unit = { _, _, _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private data class CompletedRound(
        val playerNames: Map<PlayerSymbol, String>,
        val winner: PlayerSymbol?,
        val moves: List<Cell>,
        val boardSize: Int,
    )

    private val state = MutableStateFlow(GameState(field = GameRules.emptyField(boardSize)))
    private val stateChanges = Channel<GameState>(Channel.UNLIMITED)

    // Recording a round writes to disk/SQLite. Keep that off the game lock: one
    // consumer preserves the order of rounds without stalling moves or broadcasts.
    private val completedRounds = Channel<CompletedRound>(Channel.UNLIMITED)
    private val resultsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var turnStartedAt = 0L
    private var clockX = clockBudgetMs ?: 0L
    private var clockO = clockBudgetMs ?: 0L
    private val roundMoves = mutableListOf<Cell>()
    private val lastReactionAt = ConcurrentHashMap<WebSocketSession, Long>()

    private companion object {
        const val BOT_THINK_MS = 400L
        const val REACTION_COOLDOWN_MS = 1_000L
    }

    init {
        gameScope.launch {
            for (change in stateChanges) broadcast(change)
        }
        resultsScope.launch {
            for (round in completedRounds) {
                runCatching { onRoundOver(round.playerNames, round.winner, round.moves, round.boardSize) }
                    .onFailure { logger.warn("Failed to record a finished round", it) }
            }
            resultsScope.cancel()
        }
    }

    fun connectPlayer(session: WebSocketSession): PlayerSymbol? = synchronized(lock) {
        if (secondBotLevel != null) {
            attachExhibitionBotsLocked() // a human can only watch a bot-vs-bot room
            return null
        }
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
            lastReactionAt.remove(session)
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
        playerSockets.remove(player)?.let { lastReactionAt.remove(it) }
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
        lastReactionAt.remove(session)
    }

    fun spectatorCount(): Int = spectatorSockets.size

    /** Broadcasts a whitelisted emoji to everyone in the game; a repeat inside the cooldown is dropped quietly. */
    suspend fun react(
        from: PlayerSymbol?,
        session: WebSocketSession,
        emoji: String,
    ): ErrorReason? {
        if (emoji !in REACTION_EMOJIS) return ErrorReason.INVALID_REACTION
        val now = System.currentTimeMillis()
        val last = lastReactionAt[session] ?: 0L
        if (now - last < REACTION_COOLDOWN_MS) return null
        lastReactionAt[session] = now

        val wire = protocolJson.encodeToString<ServerEvent>(ServerEvent.Reaction(from, emoji))
        for (socket in playerSockets.values) {
            try {
                socket.send(wire)
            } catch (e: Exception) {
                logger.warn("Failed to deliver a reaction to a player socket", e)
            }
        }
        for (socket in spectatorSockets) {
            try {
                socket.send(wire)
            } catch (e: Exception) {
                logger.warn("Failed to deliver a reaction to a spectator socket", e)
            }
        }
        return null
    }

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
            roundMoves.add(Cell(x, y))
            if (clockBudgetMs != null && armedTurnFor == player && turnStartedAt > 0) {
                // the mover pays for their thinking time
                val spent = System.currentTimeMillis() - turnStartedAt
                if (player == PlayerSymbol.X) clockX = (clockX - spent).coerceAtLeast(0) else clockO = (clockO - spent).coerceAtLeast(0)
            }

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
                    winningCells = win?.cells.orEmpty(),
                    score = newScore,
                    undoOfferBy = null, // the position moved on: a stale takeback offer dies
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
            val humans = humanSymbols() // the bot never asks
            if (updated.isNotEmpty() && humans.all { it in updated }) {
                roundRestartJob?.cancel()
                roundRestartJob = null
                roundRestartDeadline = 0
                roundMoves.clear()
                resetClocksLocked()
                publish(
                    current.copy(
                        playerAtTurn = PlayerSymbol.X,
                        field = GameRules.emptyField(boardSize),
                        winningPlayer = null,
                        winningCells = emptyList(),
                        isBoardFull = false,
                        isDrawAgreed = false,
                        drawPlayers = emptyList(),
                        undoOfferBy = null,
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
            val humans = humanSymbols()
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

    /** A takeback: the last mover asks, the player on turn approves; the move is reverted and the turn returns. */
    fun requestUndo(player: PlayerSymbol, session: WebSocketSession): ErrorReason? {
        synchronized(lock) {
            val current = state.value
            if (playerSockets[player] !== session) return ErrorReason.STALE_SESSION
            if (current.isRoundOver) return ErrorReason.ROUND_IS_OVER

            if (player == current.playerAtTurn) {
                // the player on turn is the approver
                val offer = current.undoOfferBy ?: return ErrorReason.NOT_YOUR_TURN
                val last = roundMoves.removeAt(roundMoves.lastIndex)
                val reverted = current.field.mapIndexed { y, row ->
                    row.mapIndexed { x, cell -> if (y == last.y && x == last.x) null else cell }
                }
                publish(
                    current.copy(
                        field = reverted,
                        playerAtTurn = offer, // the move goes back to its maker
                        undoOfferBy = null,
                        drawPlayers = emptyList(),
                        rematchPlayers = emptyList(),
                    ),
                )
                return null
            }

            if (roundMoves.isEmpty()) return ErrorReason.NOTHING_TO_UNDO
            if (current.undoOfferBy == player) {
                publish(current.copy(undoOfferBy = null)) // withdrawn
            } else {
                publish(current.copy(undoOfferBy = player))
            }
            return null
        }
    }

    fun shutdown() {
        gameScope.cancel()
        // close rather than cancel: the recorder still drains the rounds already won
        completedRounds.close()
    }

    /** True when an anonymous joiner with these settings can be paired into this game. */
    fun matches(boardSize: Int, winLength: Int, botLevel: BotLevel?, turnSeconds: Int, clockSeconds: Int = 0): Boolean = this.boardSize == boardSize &&
        this.winLength == winLength && this.botLevel == botLevel && this.turnTimeoutMs == turnSeconds * 1000L &&
        this.clockBudgetMs == clockSeconds.takeIf { it > 0 }?.let { it * 1000L }

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
                roundMoves.clear()
                resetClocksLocked()
                publish(
                    state.value.copy(
                        playerAtTurn = PlayerSymbol.X,
                        field = GameRules.emptyField(boardSize),
                        winningPlayer = null,
                        winningCells = emptyList(),
                        isBoardFull = false,
                        isDrawAgreed = false,
                        drawPlayers = emptyList(),
                        undoOfferBy = null,
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
        val withClocks = if (clockBudgetMs != null && !withTurn.isRoundOver && withTurn.connectionPlayers.size >= 2) {
            val now = System.currentTimeMillis()
            fun live(sym: PlayerSymbol): Long {
                val base = clockOf(sym)
                return if (sym == withTurn.playerAtTurn && turnStartedAt > 0) {
                    (base - (now - turnStartedAt)).coerceAtLeast(0)
                } else {
                    base
                }
            }
            withTurn.copy(clocks = mapOf(PlayerSymbol.X to live(PlayerSymbol.X), PlayerSymbol.O to live(PlayerSymbol.O)))
        } else {
            withTurn
        }
        state.value = withClocks
        stateChanges.trySend(withClocks)
        if (roundJustEnded(previous, withClocks)) {
            completedRounds.trySend(
                CompletedRound(withClocks.playerNames, withClocks.winningPlayer, roundMoves.toList(), boardSize),
            )
        }
        scheduleBotMoveIfNeeded(withClocks)
    }

    private fun roundJustEnded(previous: GameState, next: GameState): Boolean = !previous.isRoundOver && next.isRoundOver

    /** Forfeits the round to the opponent when the current player stalls past the deadline — turn budget or clock. */
    private fun armTurnTimerLocked(current: GameState) {
        if (current.isRoundOver || current.connectionPlayers.size < 2) {
            turnTimeoutJob?.cancel()
            turnTimeoutJob = null
            turnDeadline = 0
            armedTurnFor = null
            return
        }
        if (current.playerAtTurn == armedTurnFor && turnDeadline > 0) return // same turn: keep the original deadline
        val symbol = current.playerAtTurn
        armedTurnFor = symbol
        turnStartedAt = System.currentTimeMillis()
        // the clock and the turn budget share one job: whichever runs out first forfeits the round
        val clockLimit = if (symbol != null) clockOf(symbol) else Long.MAX_VALUE
        val effectiveDelay = minOf(turnTimeoutMs, if (clockBudgetMs != null) clockLimit else turnTimeoutMs)
        turnDeadline = turnStartedAt + effectiveDelay
        turnTimeoutJob?.cancel()
        turnTimeoutJob = gameScope.launch {
            delay(effectiveDelay)
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

    private fun clockOf(symbol: PlayerSymbol): Long = if (symbol == PlayerSymbol.X) clockX else clockO

    private fun resetClocksLocked() {
        clockX = clockBudgetMs ?: 0L
        clockO = clockBudgetMs ?: 0L
        turnStartedAt = 0
    }

    private fun scheduleBotMoveIfNeeded(current: GameState) {
        val symbol = current.playerAtTurn ?: return
        val level = levelFor(symbol) ?: return
        if (current.isRoundOver) return
        botMoveJob?.cancel()
        botMoveJob = gameScope.launch {
            delay(BOT_THINK_MS)
            val snapshot = synchronized(lock) {
                if (levelFor(symbol) == null) return@launch
                state.value.takeIf { it.playerAtTurn == symbol && !it.isRoundOver } ?: return@launch
            }
            // the search runs unlocked: a deep think must not stall broadcasts and joins
            val move = BotBrain.chooseMove(snapshot.field, symbol, winLength, level) ?: return@launch
            synchronized(lock) {
                if (levelFor(symbol) == null) return@launch
                val now = state.value
                // play only if the board is still the one we analyzed
                if (now.field == snapshot.field && now.playerAtTurn == symbol && !now.isRoundOver) {
                    finishTurn(symbol, BotSession, move.x, move.y)
                }
            }
        }
    }

    /** The bot level owning the slot, or null for a human slot. */
    private fun levelFor(symbol: PlayerSymbol): BotLevel? = when {
        secondBotLevel == null -> if (symbol == bot) botLevel else null
        symbol == PlayerSymbol.X -> botLevel
        else -> secondBotLevel
    }

    /** The connected players who are humans; the bots never vote. */
    private fun humanSymbols(): List<PlayerSymbol> = when {
        secondBotLevel != null -> emptyList()
        bot != null -> state.value.connectionPlayers.filter { it != bot }
        else -> state.value.connectionPlayers
    }

    private fun attachExhibitionBotsLocked() {
        if (bot != null) return
        playerSockets[PlayerSymbol.X] = BotSession
        playerSockets[PlayerSymbol.O] = BotSession
        bot = PlayerSymbol.X
        publish(state.value.copy(connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O)))
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
