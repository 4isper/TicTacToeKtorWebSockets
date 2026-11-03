package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.copiedText
import com.m4isper.tictactoe.ui.isRoundRestartTransition
import com.m4isper.tictactoe.ui.isTurnTransition
import com.m4isper.tictactoe.ui.newMoveCells
import com.m4isper.tictactoe.ui.soundCue
import com.m4isper.tictactoe.ui.SoundCue
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.errorText
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.restartText
import com.m4isper.tictactoe.ui.scoreText
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import com.m4isper.tictactoe.ui.statusText
import com.m4isper.tictactoe.ui.timerSuffix
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
    val status = document.getElementById("status") as HTMLElement
    val board = document.getElementById("board") as HTMLElement
    val errorBox = document.getElementById("error") as HTMLElement
    val scoreDiv = document.getElementById("score") as HTMLElement
    val copyLink = document.getElementById("copy-link") as? HTMLButtonElement
    val rematchButton = document.getElementById("rematch") as HTMLButtonElement
    val nameInput = document.getElementById("name") as? HTMLInputElement
    val setNameButton = document.getElementById("set-name") as? HTMLButtonElement

    val params = URLSearchParams(window.location.search)
    val room = params.get("room")
    val size = params.get("size")
    val win = params.get("win")
    val bot = params.get("bot")
    val lang = parseLang(params.get("lang"))
    val wsProtocol = if (window.location.protocol == "https:") "wss" else "ws"
    val query = listOfNotNull(
        room?.let { "room=$it" },
        size?.let { "size=$it" },
        win?.let { "win=$it" },
        bot?.let { "bot=$it" },
        params.get("lang")?.let { "lang=$it" },
    ).joinToString("&")
    val wsUrl = "$wsProtocol://${window.location.host}/play" + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    val tokenKey = "tictactoe-token" + (room?.let { ":$it" } ?: "")

    var socket: WebSocket? = null
    var mySymbol: PlayerSymbol? = null
    var pendingReconnectToken: String? = null
    var retry = 0
    var lastState: GameState? = null
    var roundDeadline = 0.0
    var turnDeadline = 0.0
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

    fun roundIsOver(s: GameState): Boolean = s.winningPlayer != null || s.isBoardFull

    fun refreshLeaderboard() {
        try {
            window.asDynamic().fetch("/leaderboard").then { response: dynamic ->
                response.json().then { data: dynamic ->
                    val rows = document.getElementById("leaderboard-rows") as HTMLElement
                    rows.innerHTML = ""
                    val entries = data.entries as Array<dynamic>
                    entries.forEachIndexed { index, entry ->
                        val li = document.createElement("li")
                        li.textContent = "${index + 1}. ${entry.name} — ${entry.stats.wins} : ${entry.stats.losses} : ${entry.stats.draws}"
                        rows.appendChild(li)
                    }
                }
            }.catch { /* the leaderboard is decorative */ }
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
            }
            countdownTimer = window.setInterval({
                val current = lastState ?: return@setInterval
                if (current.roundRestartInMs != null) return@setInterval
                val msLeft = turnDeadline - Date.now()
                if (msLeft <= 0) return@setInterval
                showStatus(statusText(current, mySymbol, lang) + timerSuffix(msLeft.toLong(), lang))
            }, 250)
        } else {
            turnDeadline = 0.0
        }

        rematchButton.style.display = if (state.roundRestartInMs != null && mySymbol != null) "block" else "none"
        val myRematch = mySymbol != null && mySymbol in state.rematchPlayers
        rematchButton.disabled = myRematch
        rematchButton.textContent = rematchButtonText(myRematch, lang)

        showStatus(statusText(state, mySymbol, lang))
        scoreDiv.textContent = scoreText(state, lang)
        playStateSounds(previous, state)
        if ((previous == null || !roundIsOver(previous)) && roundIsOver(state)) refreshLeaderboard()

        board.innerHTML = ""
        val cellPx = (minOf(window.innerWidth, window.innerHeight) * 0.8 / state.field.size)
            .toInt()
            .coerceAtMost(76)
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
                    if (newMoves.any { it.x == x && it.y == y }) append(" pop")
                }
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
            showStatus("Соединение потеряно, переподключение...")
            retry++
            window.setTimeout({ connect() }, (retry.coerceAtMost(5) * 1000))
        }
        socket?.onerror = {
            showError("Ошибка соединения")
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
    connect()
}
