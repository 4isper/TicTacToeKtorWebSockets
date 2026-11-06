package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.SharedReplay
import com.m4isper.tictactoe.ui.SoundCue
import com.m4isper.tictactoe.ui.ThemeMode
import com.m4isper.tictactoe.ui.botLabel
import com.m4isper.tictactoe.ui.cellAriaText
import com.m4isper.tictactoe.ui.clockText
import com.m4isper.tictactoe.ui.connectionErrorText
import com.m4isper.tictactoe.ui.connectionLostText
import com.m4isper.tictactoe.ui.copiedText
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.detectLang
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.errorText
import com.m4isper.tictactoe.ui.isRoundRestartTransition
import com.m4isper.tictactoe.ui.isTurnTransition
import com.m4isper.tictactoe.ui.joinQuery
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.newMoveCells
import com.m4isper.tictactoe.ui.noRoomsText
import com.m4isper.tictactoe.ui.opponentLostText
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.parseTheme
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.replayFromFragment
import com.m4isper.tictactoe.ui.replayHintText
import com.m4isper.tictactoe.ui.replayToFragment
import com.m4isper.tictactoe.ui.restartText
import com.m4isper.tictactoe.ui.scoreText
import com.m4isper.tictactoe.ui.soundCue
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import com.m4isper.tictactoe.ui.statusText
import com.m4isper.tictactoe.ui.themeIcon
import com.m4isper.tictactoe.ui.themeTitle
import com.m4isper.tictactoe.ui.timerSuffix
import com.m4isper.tictactoe.ui.turnSecondsText
import com.m4isper.tictactoe.ui.undoButtonText
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date

fun main() {
    try {
        window.asDynamic().navigator.serviceWorker.register("/sw.js").catch { /* offline support is decorative */ }
    } catch (_: Throwable) {
        // no service worker support: the page still works
    }

    GameClient().start()
}

private class GameClient {
    private val status = document.getElementById("status") as HTMLElement
    private val board = document.getElementById("board") as HTMLElement
    private val errorBox = document.getElementById("error") as HTMLElement
    private val scoreDiv = document.getElementById("score") as HTMLElement
    private val clocksDiv = document.getElementById("clocks") as HTMLElement
    private val copyLink = document.getElementById("copy-link") as? HTMLButtonElement
    private val rematchButton = document.getElementById("rematch") as HTMLButtonElement
    private val drawButton = document.getElementById("draw") as HTMLButtonElement
    private val undoButton = document.getElementById("undo") as HTMLButtonElement
    private val turnBar = document.getElementById("turn-bar") as HTMLElement
    private val turnBarFill = document.getElementById("turn-bar-fill") as HTMLElement
    private val nameInput = document.getElementById("name") as? HTMLInputElement
    private val setNameButton = document.getElementById("set-name") as? HTMLButtonElement

    private val params = URLSearchParams(window.location.search)
    private val room = params.get("room")
    private val size = params.get("size")
    private val win = params.get("win")
    private val bot = params.get("bot")
    private val turn = params.get("turn")
    private val clock = params.get("clock")

    // an explicit lang parameter wins; otherwise the browser's own preference decides
    private val lang = params.get("lang")?.let(::parseLang) ?: detectLang(window.navigator.language)
    private val preferences = Preferences(
        document.getElementById("sound") as HTMLButtonElement,
        document.getElementById("theme") as HTMLButtonElement,
        lang,
    )
    private val wsProtocol = if (window.location.protocol == "https:") "wss" else "ws"
    private val query = joinQuery(
        mapOf(
            "room" to room,
            "size" to size,
            "win" to win,
            "bot" to bot,
            "turn" to turn,
            "clock" to clock,
            "lang" to params.get("lang"),
        ),
    )
    private val wsUrl = "$wsProtocol://${window.location.host}/play" + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    private val tokenKey = "tictactoe-token" + (room?.let { ":$it" } ?: "")
    private val nameKey = "tictactoe-name"

    // answered before the game even started: the server closes the socket, so retrying cannot fix them
    private val fatalReasons = setOf(
        ErrorReason.ROOM_IS_FULL,
        ErrorReason.INVALID_ROOM_NAME,
        ErrorReason.INVALID_BOARD_SIZE,
        ErrorReason.INVALID_WIN_LENGTH,
        ErrorReason.INVALID_TURN_TIME,
        ErrorReason.INVALID_BOT_LEVEL,
        ErrorReason.INVALID_CLOCK,
    )

    private var socket: WebSocket? = null
    private var mySymbol: PlayerSymbol? = null
    private var pendingReconnectToken: String? = null
    private var retry = 0
    private var fatal = false
    private var lastState: GameState? = null
    private var roundDeadline = 0.0
    private var turnDeadline = 0.0
    private var turnTotalMs = 0.0
    private var clocksSnapshot: Map<PlayerSymbol, Long> = emptyMap()
    private var clocksBase = 0.0
    private var countdownTimer: Int? = null
    private var opponentLostTimer: Int? = null

    // mirrors the server's reconnectGraceMs: how long a dropped player's slot is held
    private val reconnectGraceSeconds = 10

    private fun send(message: ClientMessage) {
        socket?.send(protocolJson.encodeToString<ClientMessage>(message))
    }

    private fun showError(message: String) {
        errorBox.textContent = message
    }

    private fun showStatus(text: String) {
        status.textContent = text
    }

    private fun resultMark(result: String): String = when (result) {
        "WIN" -> "✓"
        "LOSS" -> "✗"
        else -> "="
    }

    /** The stepper + mini board every replay view shares; a link button carries the whole game in the URL. */
    private fun mountReplay(
        container: HTMLElement,
        boardSize: Int,
        moves: List<Cell>,
    ) {
        var index = moves.size // start at the final position

        val controls = document.createElement("div") as HTMLElement
        val prev = document.createElement("button") as HTMLButtonElement
        val next = document.createElement("button") as HTMLButtonElement
        val share = document.createElement("button") as HTMLButtonElement
        val label = document.createElement("span")
        prev.textContent = "‹"
        next.textContent = "›"
        share.textContent = "🔗"
        share.title = copyLinkText(lang)
        controls.className = "replay-controls"
        controls.appendChild(prev)
        controls.appendChild(label)
        controls.appendChild(next)
        controls.appendChild(share)

        val boardDiv = document.createElement("div") as HTMLElement
        boardDiv.style.display = "grid"
        boardDiv.style.setProperty("grid-template-columns", "repeat($boardSize, 26px)")

        fun render() {
            boardDiv.innerHTML = ""
            val cells = arrayOfNulls<String>(boardSize * boardSize)
            for (i in 0 until index) {
                val m = moves[i]
                cells[m.y * boardSize + m.x] = if (i % 2 == 0) "X" else "O"
            }
            cells.forEach { symbol ->
                val cellDiv = document.createElement("div") as HTMLElement
                cellDiv.className = "mini-cell"
                cellDiv.textContent = symbol ?: ""
                boardDiv.appendChild(cellDiv)
            }
            label.textContent = " $index/${moves.size} "
            prev.disabled = index == 0
            next.disabled = index == moves.size
        }
        prev.onclick = {
            if (index > 0) index--
            render()
        }
        next.onclick = {
            if (index < moves.size) index++
            render()
        }
        share.onclick = {
            val fragment = replayToFragment(SharedReplay(boardSize, boardSize, moves))
            window.navigator.clipboard?.writeText("${window.location.origin}${window.location.pathname}${window.location.search}#$fragment")
            share.textContent = "✓"
            window.setTimeout({ share.textContent = "🔗" }, 1_500)
        }

        container.appendChild(controls)
        container.appendChild(boardDiv)
        render()
    }

    private fun showReplay(record: dynamic, container: HTMLElement) {
        val boardSize = ((record.boardSize ?: js("3")) as Double).toInt()
        val moves = ((record.moves ?: js("[]")) as Array<dynamic>).map { m ->
            Cell((m.x as Double).toInt(), (m.y as Double).toInt())
        }
        container.innerHTML = ""
        mountReplay(container, boardSize, moves)
    }

    /** A shared link opens nothing but the replay: the board stays idle while the game plays out. */
    private fun showSharedReplay(replay: SharedReplay) {
        val host = document.getElementById("shared-replay") as HTMLElement
        host.innerHTML = ""
        host.style.display = "block"
        board.style.display = "none"
        turnBar.style.display = "none"
        (document.getElementById("reactions") as? HTMLElement)?.style?.display = "none"

        // rebuild the final position so the header can announce the outcome
        var field: List<List<PlayerSymbol?>> = GameRules.emptyField(replay.boardSize)
        replay.moves.forEachIndexed { i, cell ->
            field = field.mapIndexed { y, row ->
                row.mapIndexed { x, s -> if (y == cell.y && x == cell.x) if (i % 2 == 0) PlayerSymbol.X else PlayerSymbol.O else s }
            }
        }
        val win = GameRules.win(field, replay.winLength)
        val final = GameState(
            field = field,
            connectionPlayers = listOf(PlayerSymbol.X, PlayerSymbol.O),
            winningPlayer = win?.player,
            winningCells = win?.cells ?: emptyList(),
            isBoardFull = GameRules.isBoardFull(field),
            playerNames = buildMap {
                replay.xName?.let { put(PlayerSymbol.X, it) }
                replay.oName?.let { put(PlayerSymbol.O, it) }
            },
        )
        val title = document.createElement("h2")
        title.textContent = statusText(final, null, lang)
        host.appendChild(title)
        mountReplay(host, replay.boardSize, replay.moves)

        val back = document.createElement("button") as HTMLButtonElement
        back.textContent = when (lang) {
            Lang.RU -> "← К игре"
            Lang.EN -> "← Back to the game"
        }
        back.onclick = {
            window.location.href = "${window.location.pathname}${window.location.search}"
        }
        host.appendChild(back)
    }

    private fun showReaction(emoji: String) {
        val bubble = document.createElement("span") as HTMLElement
        bubble.className = "reaction-bubble"
        bubble.textContent = emoji
        bubble.style.left = "${(10..80).random()}%"
        board.appendChild(bubble)
        window.setTimeout({ bubble.remove() }, 2_000)
    }

    private fun showProfile(name: String) {
        try {
            val encoded = js("encodeURIComponent(name)")
            window.asDynamic().fetch("/player?name=" + encoded).then { response: dynamic ->
                val profileDiv = document.getElementById("profile") as HTMLElement
                if (response.status != 200) {
                    profileDiv.innerHTML = ""
                    return@then
                }
                response.json().then { data: dynamic ->
                    profileDiv.innerHTML = ""
                    val title = document.createElement("h3")
                    title.textContent = "${data.name as String} · ${data.stats.rating as Int}"
                    val ul = document.createElement("ul") as HTMLElement
                    val replayDiv = document.createElement("div") as HTMLElement
                    val history = (data.stats.history ?: js("[]")) as Array<dynamic>
                    history.forEach { rec ->
                        val li = document.createElement("li") as HTMLElement
                        val opponent = rec.opponent as? String
                        val d = Date(rec.epochMs as Double)
                        val stamp = "${d.getDate().toString().padStart(2, '0')}.${(d.getMonth() + 1).toString().padStart(2, '0')} " +
                            "${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}"
                        li.textContent = "${resultMark(rec.result as String)} ${if (opponent != null) "vs $opponent · " else ""}$stamp"
                        li.style.cursor = "pointer"
                        li.title = replayHintText(lang)
                        li.onclick = { showReplay(rec, replayDiv) }
                        ul.appendChild(li)
                    }
                    profileDiv.appendChild(title)
                    profileDiv.appendChild(ul)
                    profileDiv.appendChild(replayDiv)
                }
            }.catch { /* the profile is decorative */ }
        } catch (ignored: Throwable) {
            // decorative as well
        }
    }

    private fun refreshLeaderboard() {
        try {
            window.asDynamic().fetch("/leaderboard").then { response: dynamic ->
                response.json().then { data: dynamic ->
                    val rows = document.getElementById("leaderboard-rows") as HTMLElement
                    rows.innerHTML = ""
                    val entries = data.entries as Array<dynamic>
                    entries.forEachIndexed { index, entry ->
                        val li = document.createElement("li") as HTMLElement
                        li.textContent = "${index + 1}. ${entry.name} — ${entry.stats.rating} · ${entry.stats.wins} : ${entry.stats.losses} : ${entry.stats.draws}"
                        li.style.cursor = "pointer"
                        li.title = entry.name as String
                        li.onclick = { showProfile(entry.name as String) }
                        rows.appendChild(li)
                    }
                }
            }.catch { /* the leaderboard is decorative */ }
        } catch (ignored: Throwable) {
            // decorative as well
        }
    }

    private fun refreshRooms() {
        try {
            window.asDynamic().fetch("/rooms").then { response: dynamic ->
                response.json().then { data: dynamic ->
                    val rows = document.getElementById("rooms-rows") as HTMLElement
                    rows.innerHTML = ""
                    val rooms = (data.rooms ?: js("[]")) as Array<dynamic>
                    if (rooms.isEmpty()) {
                        val li = document.createElement("li")
                        li.textContent = noRoomsText(lang)
                        rows.appendChild(li)
                        return@then
                    }
                    rooms.forEach { room ->
                        val li = document.createElement("li") as HTMLElement
                        val details = buildString {
                            append("${room.boardSize}×${room.boardSize}")
                            if (room.winLength != room.boardSize) append(", win ${room.winLength}")
                            val level = room.botLevel as? String
                            if (level != null) append(", ${botLabel(lang)}: $level")
                            val seconds = room.turnSeconds as? Int
                            if (seconds != null && seconds != 30) append(", ${turnSecondsText(seconds, lang)}")
                        }
                        li.textContent = "${room.name} — ${room.players}/2 · $details"
                        li.style.cursor = "pointer"
                        li.onclick = {
                            val langParam = params.get("lang")?.let { "&lang=$it" } ?: ""
                            window.location.href = "${window.location.pathname}?room=${room.name}$langParam"
                        }
                        rows.appendChild(li)
                    }
                }
            }.catch { /* the room list is decorative */ }
        } catch (ignored: Throwable) {
            // decorative as well
        }
    }

    // -- sound: tiny synthesized cues, never a game breaker --
    private var audioCtx: dynamic = null

    private fun ensureAudio() {
        if (audioCtx != null) return
        try {
            audioCtx = js("new (window.AudioContext || window.webkitAudioContext)()")
        } catch (ignored: Throwable) {
            audioCtx = null
        }
    }

    private fun beep(freq: Double, durationMs: Int, type: String = "sine") {
        if (preferences.muted) return
        try {
            ensureAudio()
            val ctx = audioCtx ?: return
            val osc = ctx.createOscillator()
            val gain = ctx.createGain()
            osc.type = type
            osc.frequency.value = freq
            gain.gain.value = 0.04
            osc.connect(gain)
            gain.connect(ctx.destination)
            osc.start()
            osc.stop(ctx.currentTime + durationMs / 1000.0)
        } catch (ignored: Throwable) {
            // sound is decorative; never break the game over it
        }
    }

    private fun playStateSounds(previous: GameState?, state: GameState) {
        when (soundCue(previous, state, mySymbol)) {
            SoundCue.WIN -> {
                beep(523.0, 120)
                window.setTimeout({ beep(659.0, 120) }, 120)
                window.setTimeout({ beep(784.0, 220) }, 240)
            }

            SoundCue.DRAW -> beep(220.0, 250, "triangle")

            SoundCue.MOVE_MINE -> beep(660.0, 60)

            // my stone: a brighter blip

            SoundCue.MOVE_OPPONENT -> beep(440.0, 90, "triangle")

            // the opponent's: a lower knock

            SoundCue.NONE -> Unit
        }
    }

    /** The chess clocks tick locally between server states; the player on turn is the one draining. */
    private fun renderClocks() {
        val current = lastState ?: return
        fun side(sym: PlayerSymbol): String {
            var ms = clocksSnapshot[sym] ?: 0L
            if (sym == current.playerAtTurn && clocksBase > 0) ms = (ms - (Date.now() - clocksBase)).toLong().coerceAtLeast(0)
            return clockText(ms)
        }
        clocksDiv.textContent = "${side(PlayerSymbol.X)} : ${side(PlayerSymbol.O)}"
    }

    private fun showState(event: ServerEvent.StateChanged) {
        val state = event.state
        val previous = lastState
        lastState = state
        updateCountdowns(previous, state)
        updateActionButtons(state)
        updateStatus(previous, state)
        renderBoard(previous, state)
    }

    private fun updateCountdowns(previous: GameState?, state: GameState) {
        countdownTimer?.let { window.clearInterval(it) }
        countdownTimer = null
        restartCountdown(previous, state)
        turnCountdown(previous, state)
    }

    /** The connection is gone: no deadline survives it, so the countdowns stop writing the status line. */
    private fun stopCountdowns() {
        countdownTimer?.let { window.clearInterval(it) }
        countdownTimer = null
        roundDeadline = 0.0
        turnDeadline = 0.0
        turnBar.style.display = "none"
    }

    private fun restartCountdown(previous: GameState?, state: GameState) {
        if (state.roundRestartInMs != null) {
            if (isRoundRestartTransition(previous, state) || roundDeadline == 0.0) {
                roundDeadline = Date.now() + state.roundRestartInMs
            }
            countdownTimer = window.setInterval({
                val current = lastState ?: return@setInterval
                val msLeft = roundDeadline - Date.now()
                if (msLeft <= 0) return@setInterval
                if (opponentLostTimer == null) {
                    showStatus(statusText(current, mySymbol, lang) + " · " + restartText(msLeft.toLong(), lang))
                }
            }, 250)
        } else {
            roundDeadline = 0.0
        }
    }

    private fun turnCountdown(previous: GameState?, state: GameState) {
        if (state.roundRestartInMs == null && state.turnRemainingMs != null) {
            if (isTurnTransition(previous, state) || turnDeadline == 0.0) {
                turnDeadline = Date.now() + state.turnRemainingMs
                turnTotalMs = state.turnRemainingMs.toDouble()
            }
            turnBar.style.display = "block"
            turnBarFill.style.width = "100%"
            countdownTimer = window.setInterval({
                val current = lastState ?: return@setInterval
                if (current.roundRestartInMs != null) return@setInterval
                val msLeft = turnDeadline - Date.now()
                if (msLeft <= 0) return@setInterval
                turnBarFill.style.width = "${(msLeft / turnTotalMs * 100).coerceIn(0.0, 100.0)}%"
                if (clocksSnapshot.isNotEmpty()) renderClocks()
                // the away-plate owns the status line while the opponent is gone
                if (opponentLostTimer == null) {
                    showStatus(statusText(current, mySymbol, lang) + timerSuffix(msLeft.toLong(), lang))
                }
            }, 250)
        } else {
            turnDeadline = 0.0
            turnBar.style.display = "none"
        }
    }

    private fun updateActionButtons(state: GameState) {
        updateRematchButton(state)
        updateDrawButton(state)
        updateUndoButton(state)
    }

    /** The away-plate: a countdown while the reconnect grace lasts, then plain waiting until the state changes. */
    private fun startOpponentLostPlate() {
        if (opponentLostTimer != null) return
        var secondsLeft = reconnectGraceSeconds
        showStatus(opponentLostText(secondsLeft, lang))
        opponentLostTimer = window.setInterval({
            secondsLeft -= 1
            if (secondsLeft > 0) {
                showStatus(opponentLostText(secondsLeft, lang))
            } else {
                stopOpponentLostPlate()
                showStatus(opponentLostText(null, lang))
            }
        }, 1000)
    }

    private fun stopOpponentLostPlate() {
        opponentLostTimer?.let { window.clearInterval(it) }
        opponentLostTimer = null
    }

    private fun updateRematchButton(state: GameState) {
        rematchButton.style.display = if (state.roundRestartInMs != null && mySymbol != null) "block" else "none"
        val myRematch = mySymbol != null && mySymbol in state.rematchPlayers
        rematchButton.disabled = myRematch
        rematchButton.textContent = rematchButtonText(myRematch, lang)
    }

    private fun updateDrawButton(state: GameState) {
        val roundOver = state.isRoundOver
        drawButton.style.display = if (!roundOver && mySymbol != null && state.connectionPlayers.size >= 2) "block" else "none"
        val iOffered = mySymbol != null && mySymbol in state.drawPlayers
        val opponentOffered = state.drawPlayers.any { it != mySymbol }
        drawButton.textContent = drawButtonText(iOffered, opponentOffered, lang)
    }

    private fun updateUndoButton(state: GameState) {
        val roundOver = state.isRoundOver
        val undoVisible = !roundOver && mySymbol != null && state.connectionPlayers.size >= 2 &&
            (state.undoOfferBy != null || state.playerAtTurn != mySymbol)
        undoButton.style.display = if (undoVisible) "block" else "none"
        val iOfferedUndo = mySymbol != null && state.undoOfferBy == mySymbol
        val opponentOfferedUndo = state.undoOfferBy != null && state.undoOfferBy != mySymbol
        undoButton.textContent = undoButtonText(iOfferedUndo, opponentOfferedUndo, lang)
    }

    private fun updateStatus(previous: GameState?, state: GameState) {
        if (mySymbol != null && state.awayPlayers.isNotEmpty()) {
            startOpponentLostPlate()
        } else {
            stopOpponentLostPlate()
            showStatus(statusText(state, mySymbol, lang))
        }
        scoreDiv.textContent = scoreText(state, lang)
        if (state.clocks.isNotEmpty()) {
            clocksSnapshot = state.clocks
            clocksBase = Date.now()
            clocksDiv.style.display = "block"
            renderClocks()
        } else {
            clocksSnapshot = emptyMap()
            clocksDiv.style.display = "none"
        }
        playStateSounds(previous, state)
        if ((previous == null || !previous.isRoundOver) && state.isRoundOver) refreshLeaderboard()
    }

    private fun renderBoard(previous: GameState?, state: GameState) {
        board.innerHTML = ""
        val cellPx = (minOf(window.innerWidth, window.innerHeight) * 0.8 / state.field.size)
            .toInt()
            .coerceAtMost(76)
        turnBar.style.width = "${state.field.size * cellPx + (state.field.size - 1) * 4}px" // match the board width
        val winningCells = state.winningCells.map { "${it.x},${it.y}" }.toSet()
        val newMoves = newMoveCells(previous, state)
        state.field.forEachIndexed { y, row ->
            val rowDiv = document.createElement("div")
            rowDiv.className = "row"
            row.forEachIndexed { x, cell ->
                val cellButton = document.createElement("button") as HTMLButtonElement
                cellButton.className = buildString {
                    append("cell")
                    if ("$x,$y" in winningCells) append(" winning")
                    if (newMoves.any { it.x == x && it.y == y }) append(" pop last-move")
                }
                cellButton.setAttribute("aria-label", cellAriaText(x, y, cell, lang))
                cellButton.style.width = "${cellPx}px"
                cellButton.style.height = "${cellPx}px"
                cellButton.style.fontSize = "${cellPx / 2}px"
                cellButton.textContent = when (cell) {
                    PlayerSymbol.X -> "X"
                    PlayerSymbol.O -> "O"
                    null -> ""
                }
                cellButton.disabled =
                    cell != null || mySymbol == null || state.connectionPlayers.size < 2 || state.playerAtTurn != mySymbol
                cellButton.onclick = {
                    ensureAudio()
                    send(ClientMessage.MakeTurn(x = x, y = y))
                }
                rowDiv.appendChild(cellButton)
            }
            board.appendChild(rowDiv)
        }
    }

    private fun handleMessage(text: String) {
        when (val event = protocolJson.decodeFromString<ServerEvent>(text)) {
            is ServerEvent.Joined -> {
                mySymbol = event.symbol
                localStorage.setItem(tokenKey, event.token)
                showError("")
            }

            is ServerEvent.StateChanged -> {
                pendingReconnectToken?.let { localStorage.setItem(tokenKey, it) }
                pendingReconnectToken = null
                // the server is the source of truth: cache the name it attributes to me
                val myName = mySymbol?.let { symbol -> event.state.playerNames[symbol] }
                if (myName != null) localStorage.setItem(nameKey, myName)
                showState(event)
            }

            is ServerEvent.Error -> {
                pendingReconnectToken = null
                if (event.reason == ErrorReason.UNKNOWN_TOKEN) localStorage.removeItem(tokenKey)
                if (event.reason in fatalReasons) fatal = true
                showError(errorText(event.reason, lang))
            }

            is ServerEvent.Reaction -> showReaction(event.emoji)
        }
    }

    private fun connect() {
        socket = WebSocket(wsUrl)
        socket?.onopen = {
            retry = 0
            showError("")
            val stored = localStorage.getItem(tokenKey)
            if (stored != null) {
                pendingReconnectToken = stored
                send(ClientMessage.Reconnect(stored))
            }
            val storedName = localStorage.getItem(nameKey)
            if (!storedName.isNullOrEmpty()) send(ClientMessage.SetName(storedName))
        }
        socket?.onmessage = { event ->
            handleMessage(event.data.toString())
        }
        socket?.onclose = {
            stopOpponentLostPlate()
            stopCountdowns()
            if (fatal) {
                // the join was rejected: the reason is in the error box, there is nothing to reconnect to
                showStatus("")
            } else {
                showStatus(connectionLostText(lang))
                retry++
                window.setTimeout({ connect() }, (retry.coerceAtMost(5) * 1000))
            }
        }
        socket?.onerror = {
            showError(connectionErrorText(lang))
        }
    }

    fun start() {
        copyLink?.let { button ->
            button.onclick = {
                window.navigator.clipboard?.writeText(window.location.href)
                button.textContent = copiedText(lang)
                window.setTimeout({ button.textContent = copyLinkText(lang) }, 2000)
            }
        }

        rematchButton.onclick = { send(ClientMessage.RequestRematch) }
        drawButton.onclick = { send(ClientMessage.RequestDraw) }
        undoButton.onclick = { send(ClientMessage.RequestUndo) }

        document.querySelectorAll("#reactions button").asDynamic().forEach { node: dynamic ->
            (node as? HTMLButtonElement)?.let { button ->
                button.onclick = { send(ClientMessage.React(button.textContent ?: "")) }
            }
        }

        if (nameInput != null && setNameButton != null) {
            nameInput.value = localStorage.getItem(nameKey) ?: ""
            fun submitName() {
                val name = nameInput.value.trim()
                if (name.isNotEmpty()) send(ClientMessage.SetName(name))
                nameInput.value = ""
            }
            setNameButton.onclick = { submitName() }
            nameInput.onkeydown = { event ->
                if (event.asDynamic().key == "Enter") submitName()
            }
        }

        refreshLeaderboard()
        window.setInterval({ refreshLeaderboard() }, 30_000) // the table also moves while nobody finishes a round
        refreshRooms()
        window.setInterval({ refreshRooms() }, 5_000)

        val sharedReplay = replayFromFragment(window.location.hash.removePrefix("#"))
        if (sharedReplay != null) {
            showSharedReplay(sharedReplay) // the link carries the whole game: no connection needed
        } else {
            connect()
        }
    }
}

/** The sound and theme toggles: their icons, hints and localStorage persistence. */
private class Preferences(
    private val soundButton: HTMLButtonElement,
    private val themeButton: HTMLButtonElement,
    private val lang: Lang,
) {
    var muted = localStorage.getItem(MUTED_KEY) == "1"
        private set
    var theme = parseTheme(localStorage.getItem(THEME_KEY))
        private set

    init {
        soundButton.onclick = {
            muted = !muted
            localStorage.setItem(MUTED_KEY, if (muted) "1" else "0")
            renderSound()
        }
        themeButton.onclick = {
            theme = when (theme) {
                ThemeMode.AUTO -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.AUTO
            }
            localStorage.setItem(THEME_KEY, theme.name.lowercase())
            renderTheme()
        }
        renderSound()
        renderTheme()
    }

    private fun renderSound() {
        soundButton.textContent = soundIcon(muted)
        soundButton.title = soundTitle(muted, lang)
    }

    private fun renderTheme() {
        val root = document.documentElement as HTMLElement
        if (theme == ThemeMode.AUTO) {
            root.removeAttribute("data-theme")
        } else {
            root.setAttribute("data-theme", theme.name.lowercase())
        }
        themeButton.textContent = themeIcon(theme)
        themeButton.title = themeTitle(theme, lang)
    }

    private companion object {
        const val MUTED_KEY = "tictactoe-muted"
        const val THEME_KEY = "tictactoe-theme"
    }
}
