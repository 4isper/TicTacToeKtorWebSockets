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
    /** Wins needed to take the series; null keeps the score open-ended. */
    val seriesTarget: Int? = null,
    private val onRoundOver: (playerNames: Map<PlayerSymbol, String>, winner: PlayerSymbol?, moves: List<Cell>, boardSize: Int) -> Unit = { _, _, _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(TicTacToeGame::class.java)

    private data class CompletedRound(
        val playerNames: Map<PlayerSymbol, String>,
        val winner: PlayerSymbol?,
        val moves: List<Cell>,
        val boardSize: Int,
    )

    private val state = MutableStateFlow(GameState(field = GameRules.emptyField(boardSize), winLength = winLength, seriesTarget = seriesTarget))
    private val stateChanges = Channel<GameState>(Channel.UNLIMITED)

    // Recording a round writes to disk/SQLite. Keep that off the game lock: one
    // consumer preserves the order of rounds without stalling moves or broadcasts.
    private val completedRounds = Channel<CompletedRound>(Channel.UNLIMITED)
    private val resultsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val playerSockets = ConcurrentHashMap<PlayerSymbol, WebSocketSession>()
    private val spectatorSockets = ConcurrentHashMap.newKeySet<WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // negamax is CPU-bound: keep deep searches off the pool that serves broadcasts and timers
    private val botScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(2))
    private var roundRestartJob: Job? = null
    private var roundRestartDeadline = 0L
    private var bot: PlayerSymbol? = null
    private var roundStarter: PlayerSymbol = PlayerSymbol.X
    private var botMoveJob: Job? = null
    private var turnTimeoutJob: Job? = null
    private var turnDeadline = 0L
    private var armedTurnFor: PlayerSymbol? = null
    private var turnStartedAt = 0L
    private var clockX = clockBudgetMs ?: 0L
    private var clockO = clockBudgetMs ?: 0L
    private val roundMoves = mutableListOf<Cell>()
    private val lastReactionAt = ConcurrentHashMap<WebSocketSession, Long>()
    private val lastChatAt = ConcurrentHashMap<WebSocketSession, Long>()

    private companion object {
        const val BOT_THINK_MS = 400L
        const val REACTION_COOLDOWN_MS = 1_000L
        const val CHAT_COOLDOWN_MS = 500L
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
        if (playerSockets[player] !== session) {
            false
        } else {
            playerSockets.remove(player)
            lastReactionAt.remove(session)
            lastChatAt.remove(session)
            // the slot is held for the reconnect grace: everyone sees the player went away
            publish(state.value.copy(awayPlayers = state.value.awayPlayers + player))
            true
        }
    }

    fun reattachSession(player: PlayerSymbol, session: WebSocketSession): Unit = synchronized(lock) {
        playerSockets[player] = session
        val current = state.value
        val connections = if (player in current.connectionPlayers) current.connectionPlayers else current.connectionPlayers + player
        val away = current.awayPlayers - player
        if (connections != current.connectionPlayers || away != current.awayPlayers) {
            publish(current.copy(connectionPlayers = connections, awayPlayers = away))
        }
    }

    fun disconnectPlayer(player: PlayerSymbol): Unit = synchronized(lock) {
        playerSockets.remove(player)?.let {
            lastReactionAt.remove(it)
            lastChatAt.remove(it)
        }
        publish(
            state.value.copy(
                connectionPlayers = state.value.connectionPlayers - player,
                playerNames = state.value.playerNames - player,
                awayPlayers = state.value.awayPlayers - player,
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
        lastChatAt.remove(session)
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
        broadcastWire(protocolJson.encodeToString<ServerEvent>(ServerEvent.Reaction(from, emoji)))
        return null
    }

    /** Broadcasts a validated chat line to players and spectators; a repeat inside the cooldown is dropped. */
    suspend fun chat(
        from: PlayerSymbol?,
        name: String?,
        session: WebSocketSession,
        text: String,
    ) {
        val now = System.currentTimeMillis()
        val last = lastChatAt[session] ?: 0L
        if (now - last < CHAT_COOLDOWN_MS) return
        lastChatAt[session] = now
        broadcastWire(protocolJson.encodeToString<ServerEvent>(ServerEvent.Chat(from, name, text)))
    }

    /** One wire string, every socket in the room: reactions and chat share the delivery path. */
    private suspend fun broadcastWire(wire: String) {
        for ((player, socket) in playerSockets) {
            try {
                socket.send(wire)
            } catch (e: Exception) {
                logger.warn("Failed to deliver a social event to a player socket", e)
                synchronized(lock) {
                    if (playerSockets[player] === socket) disconnectPlayer(player)
                }
            }
        }
        for (socket in spectatorSockets) {
            try {
                socket.send(wire)
            } catch (e: Exception) {
                logger.warn("Failed to deliver a social event to a spectator socket", e)
                spectatorSockets.remove(socket)
            }
        }
    }

    suspend fun broadcast(state: GameState) {
        // one encoding for the whole room: up to 22 recipients used to re-encode the same snapshot
        val wire = protocolJson.encodeToString<ServerEvent>(ServerEvent.StateChanged(state))
        for ((player, socket) in playerSockets) {
            try {
                socket.send(wire)
            } catch (e: Exception) {
                logger.warn("Failed to broadcast game state to a player socket", e)
                synchronized(lock) {
                    if (playerSockets[player] === socket) disconnectPlayer(player)
                }
            }
        }
        for (socket in spectatorSockets) {
            try {
                socket.send(wire)
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

            val next = applyMove(current, player, x, y)
            val roundEnded = next.winningPlayer != null || next.isBoardFull
            if (roundEnded) {
                roundRestartDeadline = System.currentTimeMillis() + roundRestartDelayMs
            }
            publish(next)
            if (roundEnded) {
                scheduleRoundRestart()
            }
            return null
        }
    }

    /** Pure: the position after a legal move, with the winner and score worked out. */
    private fun applyMove(
        current: GameState,
        player: PlayerSymbol,
        x: Int,
        y: Int,
    ): GameState {
        val newField = current.field.mapIndexed { row, cells ->
            cells.mapIndexed { col, cell -> if (row == y && col == x) player else cell }
        }
        val win = GameRules.win(newField, winLength)
        val moved = current.copy(
            playerAtTurn = player.other,
            field = newField,
            isBoardFull = GameRules.isBoardFull(newField),
            winningPlayer = win?.player,
            winningCells = win?.cells.orEmpty(),
            undoOfferBy = null, // the position moved on: a stale takeback offer dies
        )
        return if (win != null) moved.scored(win.player) else moved
    }

    /** One win for [winner]: the score bumps, and a reached series target decides the series. */
    private fun GameState.scored(winner: PlayerSymbol): GameState {
        val wins = (score[winner] ?: 0) + 1
        return copy(
            score = score + (winner to wins),
            seriesWinner = this@TicTacToeGame.seriesTarget?.takeIf { wins >= it }?.let { winner },
        )
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
                beginFreshRoundLocked()
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

    /** A resignation: the asking player forfeits at once, like a run-out clock would. */
    fun requestResign(player: PlayerSymbol, session: WebSocketSession): ErrorReason? {
        synchronized(lock) {
            val current = state.value
            if (playerSockets[player] !== session) return ErrorReason.STALE_SESSION
            if (current.isRoundOver) return ErrorReason.ROUND_IS_OVER

            roundRestartDeadline = System.currentTimeMillis() + roundRestartDelayMs
            scheduleRoundRestart()
            publish(
                current.copy(
                    winningPlayer = player.other,
                    winningCells = emptyList(),
                    rematchPlayers = emptyList(),
                    drawPlayers = emptyList(),
                    undoOfferBy = null,
                ).scored(player.other),
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
        botScope.cancel()
        // close rather than cancel: the recorder still drains the rounds already won
        completedRounds.close()
    }

    /** True when an anonymous joiner with these settings can be paired into this game. */
    fun matches(
        boardSize: Int,
        winLength: Int,
        botLevel: BotLevel?,
        turnSeconds: Int,
        clockSeconds: Int = 0,
        seriesTarget: Int = 0,
        secondBotLevel: BotLevel? = null,
    ): Boolean = this.boardSize == boardSize &&
        this.winLength == winLength && this.botLevel == botLevel && this.turnTimeoutMs == turnSeconds * 1000L &&
        this.clockBudgetMs == clockSeconds.takeIf { it > 0 }?.let { it * 1000L } &&
        this.seriesTarget == seriesTarget.takeIf { it > 0 } &&
        this.secondBotLevel == secondBotLevel

    fun isFull(): Boolean = state.value.connectionPlayers.size >= 2

    fun isEmpty(): Boolean = state.value.connectionPlayers.isEmpty()

    /** Only bots (or nobody) hold the slots and nobody is watching — the lobby may retire this game. */
    fun isBotOnly(): Boolean = playerSockets.values.all { it === BotSession } && spectatorSockets.isEmpty()

    fun currentState(): GameState = state.value

    /** The durable essence of the round, for surviving a server restart; live connections and timers are excluded. */
    fun snapshot(): GameSnapshot = synchronized(lock) {
        GameSnapshot(
            state = state.value.copy(
                connectionPlayers = emptyList(),
                awayPlayers = emptyList(),
                playerNames = emptyMap(),
                turnRemainingMs = null,
                roundRestartInMs = null,
                rematchPlayers = emptyList(),
                drawPlayers = emptyList(),
                undoOfferBy = null,
                clocks = emptyMap(),
            ),
            roundStarter = roundStarter,
            moves = roundMoves.toList(),
            clockX = clockX,
            clockO = clockO,
        )
    }

    /** Resumes a persisted round in an empty room; timers stay idle until players rejoin, then arm fresh. */
    fun restoreFrom(snapshot: GameSnapshot) = synchronized(lock) {
        roundStarter = snapshot.roundStarter
        roundMoves.clear()
        roundMoves.addAll(snapshot.moves)
        clockX = snapshot.clockX
        clockO = snapshot.clockO
        state.value = snapshot.state
        if (snapshot.state.isRoundOver) {
            beginFreshRoundLocked() // a round that ended before the restart resumes as the next one
        }
    }

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
                beginFreshRoundLocked()
            }
        }
    }

    /** Starts the next round from an empty board; the opening turn alternates between rounds. */
    private fun beginFreshRoundLocked() {
        roundStarter = roundStarter.other
        roundMoves.clear()
        resetClocksLocked()
        val seriesTaken = state.value.seriesWinner != null
        publish(
            state.value.copy(
                playerAtTurn = roundStarter,
                field = GameRules.emptyField(boardSize),
                winningPlayer = null,
                winningCells = emptyList(),
                isBoardFull = false,
                isDrawAgreed = false,
                drawPlayers = emptyList(),
                undoOfferBy = null,
                roundRestartInMs = null,
                rematchPlayers = emptyList(),
                // a decided series is over: the next round starts a new one from zero
                score = if (seriesTaken) mapOf(PlayerSymbol.X to 0, PlayerSymbol.O to 0) else state.value.score,
                seriesWinner = null,
            ),
        )
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
        if (!previous.isRoundOver && withClocks.isRoundOver) {
            completedRounds.trySend(
                CompletedRound(withClocks.playerNames, withClocks.winningPlayer, roundMoves.toList(), boardSize),
            )
        }
        scheduleBotMoveIfNeeded(withClocks)
    }

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
                    snapshot
                        .copy(
                            winningPlayer = winner,
                            winningCells = emptyList(),
                            rematchPlayers = emptyList(),
                        )
                        .scored(winner),
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
        botMoveJob = botScope.launch {
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
