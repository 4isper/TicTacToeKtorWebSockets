package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.SoundCue
import com.m4isper.tictactoe.ui.botLabel
import com.m4isper.tictactoe.ui.cellAriaText
import com.m4isper.tictactoe.ui.connectionErrorText
import com.m4isper.tictactoe.ui.connectionLostText
import com.m4isper.tictactoe.ui.copiedText
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.errorText
import com.m4isper.tictactoe.ui.isRoundRestartTransition
import com.m4isper.tictactoe.ui.isTurnTransition
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.newMoveCells
import com.m4isper.tictactoe.ui.noRoomsText
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.replayHintText
import com.m4isper.tictactoe.ui.restartText
import com.m4isper.tictactoe.ui.scoreText
import com.m4isper.tictactoe.ui.soundCue
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import com.m4isper.tictactoe.ui.statusText
import com.m4isper.tictactoe.ui.timerSuffix
import com.m4isper.tictactoe.ui.turnSecondsText
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

    val status = document.getElementById("status") as HTMLElement
    val board = document.getElementById("board") as HTMLElement
    val errorBox = document.getElementById("error") as HTMLElement
    val scoreDiv = document.getElementById("score") as HTMLElement
    val copyLink = document.getElementById("copy-link") as? HTMLButtonElement
    val rematchButton = document.getElementById("rematch") as HTMLButtonElement
    val drawButton = document.getElementById("draw") as HTMLButtonElement
    val turnBar = document.getElementById("turn-bar") as HTMLElement
    val turnBarFill = document.getElementById("turn-bar-fill") as HTMLElement
    val nameInput = document.getElementById("name") as? HTMLInputElement
    val setNameButton = document.getElementById("set-name") as? HTMLButtonElement

    val params = URLSearchParams(window.location.search)
    val room = params.get("room")
    val size = params.get("size")
    val win = params.get("win")
    val bot = params.get("bot")
    val turn = params.get("turn")
    val lang = parseLang(params.get("lang"))
    val wsProtocol = if (window.location.protocol == "https:") "wss" else "ws"
    val query = listOfNotNull(
        room?.let { "room=$it" },
        size?.let { "size=$it" },
        win?.let { "win=$it" },
        bot?.let { "bot=$it" },
        turn?.let { "turn=$it" },
        params.get("lang")?.let { "lang=$it" },
    ).joinToString("&")
    val wsUrl = "$wsProtocol://${window.location.host}/play" + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    val tokenKey = "tictactoe-token" + (room?.let { ":$it" } ?: "")
    // answered before the game even started: the server closes the socket, so retrying cannot fix them
    val fatalReasons = setOf(
        ErrorReason.ROOM_IS_FULL,
        ErrorReason.INVALID_ROOM_NAME,
        ErrorReason.INVALID_BOARD_SIZE,
        ErrorReason.INVALID_WIN_LENGTH,
        ErrorReason.INVALID_TURN_TIME,
        ErrorReason.INVALID_BOT_LEVEL,
    )

    var socket: WebSocket? = null
    var mySymbol: PlayerSymbol? = null
    var pendingReconnectToken: String? = null
    var retry = 0
    var fatal = false
    var lastState: GameState? = null
    var roundDeadline = 0.0
    var turnDeadline = 0.0
    var turnTotalMs = 0.0
    var countdownTimer: Int? = null
    var muted = localStorage.getItem("tictactoe-muted") == "1"

    fun send(message: ClientMessage) {
        socket?.send(protocolJson.encodeToString<ClientMessage>(message))
    }

    fun showError(message: String) {
        errorBox.textContent = message
    }

    fun showStatus(text: String) {
        status.textContent = text
    }

    fun resultMark(result: String): String = when (result) {
        "WIN" -> "✓"
        "LOSS" -> "✗"
        else -> "="
    }

    fun showReplay(record: dynamic, container: HTMLElement) {
        container.innerHTML = ""
        val boardSize = ((record.boardSize ?: js("3")) as Double).toInt()
        val moves = (record.moves ?: js("[]")) as Array<dynamic>
        var index = moves.size // start at the final position

        val controls = document.createElement("div") as HTMLElement
        val prev = document.createElement("button") as HTMLButtonElement
        val next = document.createElement("button") as HTMLButtonElement
        val label = document.createElement("span")
        prev.textContent = "‹"
        next.textContent = "›"
        controls.className = "replay-controls"
        controls.appendChild(prev)
        controls.appendChild(label)
        controls.appendChild(next)

        val boardDiv = document.createElement("div") as HTMLElement
        boardDiv.style.display = "grid"
        boardDiv.style.setProperty("grid-template-columns", "repeat($boardSize, 26px)")

        fun render() {
            boardDiv.innerHTML = ""
            val cells = arrayOfNulls<String>(boardSize * boardSize)
            for (i in 0 until index) {
                val m = moves[i]
                cells[((m.y as Double).toInt() * boardSize) + (m.x as Double).toInt()] = if (i % 2 == 0) "X" else "O"
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

        container.appendChild(controls)
        container.appendChild(boardDiv)
        render()
    }

    fun showProfile(name: String) {
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
                    title.textContent = data.name as String
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
        } catch (t: Throwable) {
            // decorative as well
        }
    }

    fun refreshLeaderboard() {
        try {
            window.asDynamic().fetch("/leaderboard").then { response: dynamic ->
                response.json().then { data: dynamic ->
                    val rows = document.getElementById("leaderboard-rows") as HTMLElement
                    rows.innerHTML = ""
                    val entries = data.entries as Array<dynamic>
                    entries.forEachIndexed { index, entry ->
                        val li = document.createElement("li") as HTMLElement
                        li.textContent = "${index + 1}. ${entry.name} — ${entry.stats.wins} : ${entry.stats.losses} : ${entry.stats.draws}"
                        li.style.cursor = "pointer"
                        li.title = entry.name as String
                        li.onclick = { showProfile(entry.name as String) }
                        rows.appendChild(li)
                    }
                }
            }.catch { /* the leaderboard is decorative */ }
        } catch (t: Throwable) {
            // decorative as well
        }
    }

    fun refreshRooms() {
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
        } catch (t: Throwable) {
            // decorative as well
        }
    }

    // -- sound: tiny synthesized cues, never a game breaker --
    var audioCtx: dynamic = null

    fun ensureAudio() {
        if (audioCtx != null) return
        try {
            audioCtx = js("new (window.AudioContext || window.webkitAudioContext)()")
        } catch (t: Throwable) {
            audioCtx = null
        }
    }

    fun beep(freq: Double, durationMs: Int, type: String = "sine") {
        if (muted) return
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
        } catch (t: Throwable) {
            // sound is decorative; never break the game over it
        }
    }

    fun playStateSounds(previous: GameState?, state: GameState) {
        when (soundCue(previous, state)) {
            SoundCue.WIN -> {
                beep(523.0, 120)
                window.setTimeout({ beep(659.0, 120) }, 120)
                window.setTimeout({ beep(784.0, 220) }, 240)
            }

            SoundCue.DRAW -> beep(220.0, 250, "triangle")

            SoundCue.MOVE -> beep(660.0, 60)

            SoundCue.NONE -> Unit
        }
    }

    fun showState(event: ServerEvent.StateChanged) {
        val state = event.state
        val previous = lastState
        lastState = state

        countdownTimer?.let { window.clearInterval(it) }
        countdownTimer = null
        if (state.roundRestartInMs != null) {
            if (isRoundRestartTransition(previous, state) || roundDeadline == 0.0) {
                roundDeadline = Date.now() + state.roundRestartInMs
            }
            countdownTimer = window.setInterval({
                val current = lastState ?: return@setInterval
                val msLeft = roundDeadline - Date.now()
                if (msLeft <= 0) return@setInterval
                showStatus(statusText(current, mySymbol, lang) + " · " + restartText(msLeft.toLong(), lang))
            }, 250)
        } else {
            roundDeadline = 0.0
        }
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
                showStatus(statusText(current, mySymbol, lang) + timerSuffix(msLeft.toLong(), lang))
            }, 250)
        } else {
            turnDeadline = 0.0
            turnBar.style.display = "none"
        }

        rematchButton.style.display = if (state.roundRestartInMs != null && mySymbol != null) "block" else "none"
        val myRematch = mySymbol != null && mySymbol in state.rematchPlayers
        rematchButton.disabled = myRematch
        rematchButton.textContent = rematchButtonText(myRematch, lang)

        val roundOver = state.isRoundOver
        drawButton.style.display = if (!roundOver && mySymbol != null && state.connectionPlayers.size >= 2) "block" else "none"
        val iOffered = mySymbol != null && mySymbol in state.drawPlayers
        val opponentOffered = state.drawPlayers.any { it != mySymbol }
        drawButton.textContent = drawButtonText(iOffered, opponentOffered, lang)

        showStatus(statusText(state, mySymbol, lang))
        scoreDiv.textContent = scoreText(state, lang)
        playStateSounds(previous, state)
        if ((previous == null || !previous.isRoundOver) && state.isRoundOver) refreshLeaderboard()

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

    fun handleMessage(text: String) {
        when (val event = protocolJson.decodeFromString<ServerEvent>(text)) {
            is ServerEvent.Joined -> {
                mySymbol = event.symbol
                localStorage.setItem(tokenKey, event.token)
                showError("")
            }

            is ServerEvent.StateChanged -> {
                pendingReconnectToken?.let { localStorage.setItem(tokenKey, it) }
                pendingReconnectToken = null
                showState(event)
            }

            is ServerEvent.Error -> {
                pendingReconnectToken = null
                if (event.reason == ErrorReason.UNKNOWN_TOKEN) localStorage.removeItem(tokenKey)
                if (event.reason in fatalReasons) fatal = true
                showError(errorText(event.reason, lang))
            }
        }
    }

    fun connect() {
        socket = WebSocket(wsUrl)
        socket?.onopen = {
            retry = 0
            showError("")
            val stored = localStorage.getItem(tokenKey)
            if (stored != null) {
                pendingReconnectToken = stored
                send(ClientMessage.Reconnect(stored))
            }
        }
        socket?.onmessage = { event ->
            handleMessage(event.data.toString())
        }
        socket?.onclose = {
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

    copyLink?.let { button ->
        button.onclick = {
            window.navigator.clipboard?.writeText(window.location.href)
            button.textContent = copiedText(lang)
            window.setTimeout({ button.textContent = copyLinkText(lang) }, 2000)
        }
    }

    val soundButton = document.getElementById("sound") as HTMLButtonElement

    fun renderSound() {
        soundButton.textContent = soundIcon(muted)
        soundButton.title = soundTitle(muted, lang)
    }

    soundButton.onclick = {
        muted = !muted
        localStorage.setItem("tictactoe-muted", if (muted) "1" else "0")
        renderSound()
    }
    renderSound()

    rematchButton.onclick = { send(ClientMessage.RequestRematch) }
    drawButton.onclick = { send(ClientMessage.RequestDraw) }

    if (nameInput != null && setNameButton != null) {
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
    connect()
}
