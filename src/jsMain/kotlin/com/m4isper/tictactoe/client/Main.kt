package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.BotBrain
import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.MAX_AUTO_RETRIES
import com.m4isper.tictactoe.ui.SharedReplay
import com.m4isper.tictactoe.ui.SoundCue
import com.m4isper.tictactoe.ui.ThemeMode
import com.m4isper.tictactoe.ui.autoplayText
import com.m4isper.tictactoe.ui.botLabel
import com.m4isper.tictactoe.ui.cellNotation
import com.m4isper.tictactoe.ui.clockText
import com.m4isper.tictactoe.ui.connectingText
import com.m4isper.tictactoe.ui.connectionErrorText
import com.m4isper.tictactoe.ui.connectionLostText
import com.m4isper.tictactoe.ui.copiedText
import com.m4isper.tictactoe.ui.copyFailedText
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.detectLang
import com.m4isper.tictactoe.ui.dismissText
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.errorText
import com.m4isper.tictactoe.ui.headToHeadTitle
import com.m4isper.tictactoe.ui.hintAvailable
import com.m4isper.tictactoe.ui.installText
import com.m4isper.tictactoe.ui.isRoundRestartTransition
import com.m4isper.tictactoe.ui.isTurnTransition
import com.m4isper.tictactoe.ui.joinQuery
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.noRoomsText
import com.m4isper.tictactoe.ui.opponentLostText
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.parseTheme
import com.m4isper.tictactoe.ui.profileNotFoundText
import com.m4isper.tictactoe.ui.ratingSparklinePoints
import com.m4isper.tictactoe.ui.reconnectingText
import com.m4isper.tictactoe.ui.reloadText
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.replayFromFragment
import com.m4isper.tictactoe.ui.replayHintText
import com.m4isper.tictactoe.ui.replayToFragment
import com.m4isper.tictactoe.ui.restartText
import com.m4isper.tictactoe.ui.retryDelayMs
import com.m4isper.tictactoe.ui.retryNowText
import com.m4isper.tictactoe.ui.scoreText
import com.m4isper.tictactoe.ui.seriesTargetText
import com.m4isper.tictactoe.ui.shouldAlertTurn
import com.m4isper.tictactoe.ui.soundCue
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import com.m4isper.tictactoe.ui.spectatorText
import com.m4isper.tictactoe.ui.statusText
import com.m4isper.tictactoe.ui.themeColor
import com.m4isper.tictactoe.ui.themeIcon
import com.m4isper.tictactoe.ui.themeTitle
import com.m4isper.tictactoe.ui.timerSuffix
import com.m4isper.tictactoe.ui.turnAlertBody
import com.m4isper.tictactoe.ui.turnAlertTitle
import com.m4isper.tictactoe.ui.turnSecondsText
import com.m4isper.tictactoe.ui.undoButtonText
import com.m4isper.tictactoe.ui.updateNoticeText
import com.m4isper.tictactoe.ui.urlEncode
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.WebSocket
import org.w3c.dom.Worker
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Date
import kotlin.js.json

private var deferredInstallPrompt: Event? = null

fun main() {
    // the same bundle is loaded as a Web Worker for the hint search: no window, no DOM
    if (js("typeof window") == "undefined") {
        hintWorkerMain()
        return
    }
    val lang = clientLang()
    registerServiceWorker(lang)
    captureInstallPrompt(lang)
    GameClient().start()
}

/** Worker mode of the bundle: answer a hint request with the hard bot's suggested cell. */
private fun hintWorkerMain() {
    val scope = js("self")
    scope.onmessage = { event: dynamic ->
        val data = event.data
        val size = data.size as Int
        val field = (0 until size).map { y ->
            (0 until size).map { x ->
                val cell = data.field[y][x]
                if (cell == null) null else PlayerSymbol.valueOf(cell as String)
            }
        }
        val me = PlayerSymbol.valueOf(data.symbol as String)
        val move = BotBrain.chooseMove(field, me, data.winLength as Int, BotLevel.HARD)
        scope.postMessage(move?.let { json("x" to it.x, "y" to it.y) })
    }
}

/** One language decision, shared by the page and the PWA notices. */
private fun clientLang(): Lang = URLSearchParams(window.location.search).get("lang")?.let(::parseLang)
    ?: detectLang(window.navigator.language)

/** Offer a reload only when a new worker replaces one that already controlled the page. */
private fun registerServiceWorker(lang: Lang) {
    try {
        val container = window.asDynamic().navigator.serviceWorker
        if (container == null) return
        val hadController = container.controller != null
        container.register("/sw.js")
            .then { registration: dynamic ->
                registration.addEventListener("updatefound", {
                    val worker = registration.installing
                    if (worker != null) {
                        worker.addEventListener("statechange", {
                            if (worker.state == "installed" && hadController) showUpdateNotice(lang)
                        })
                    }
                })
            }
            .catch { /* registration blocked: the page still works online */ }
        container.addEventListener("controllerchange", {
            if (hadController) showUpdateNotice(lang)
        })
    } catch (_: Throwable) {
        // no service worker support: the page still works
    }
}

private fun showUpdateNotice(lang: Lang) {
    if (document.getElementById("pwa-notice") != null) return
    val notice = document.createElement("div") as HTMLElement
    notice.id = "pwa-notice"
    notice.className = "pwa-notice"
    val text = document.createElement("span") as HTMLElement
    text.textContent = updateNoticeText(lang)
    val reload = document.createElement("button") as HTMLButtonElement
    reload.className = "secondary"
    reload.textContent = reloadText(lang)
    reload.onclick = { window.location.reload() }
    val dismiss = document.createElement("button") as HTMLButtonElement
    dismiss.className = "secondary"
    dismiss.textContent = dismissText(lang)
    dismiss.onclick = { notice.remove() }
    notice.appendChild(text)
    notice.appendChild(reload)
    notice.appendChild(dismiss)
    document.body?.appendChild(notice)
}

private fun captureInstallPrompt(lang: Lang) {
    window.addEventListener("beforeinstallprompt", { event ->
        event.preventDefault()
        deferredInstallPrompt = event
        showInstallButton(lang)
    })
}

private fun showInstallButton(lang: Lang) {
    if (document.getElementById("install-button") != null) return
    val button = document.createElement("button") as HTMLButtonElement
    button.id = "install-button"
    button.className = "secondary"
    button.textContent = installText(lang)
    button.onclick = {
        val prompt = deferredInstallPrompt
        if (prompt != null) {
            prompt.asDynamic().prompt()
            deferredInstallPrompt = null
            button.remove()
        }
    }
    document.body?.appendChild(button)
}

/** Clipboard writes are async and absent on insecure origins: only a real success shows success text. */
private fun copyTextWithFeedback(
    text: String,
    button: HTMLButtonElement,
    successText: String,
    failureText: String,
    restoreText: String,
) {
    val done: (Boolean) -> Unit = { ok ->
        button.textContent = if (ok) successText else failureText
        window.setTimeout({ button.textContent = restoreText }, 2_000)
    }
    val clipboard = window.asDynamic().navigator.clipboard
    if (clipboard != null) {
        try {
            clipboard.writeText(text).then { done(true) }.catch { legacyCopy(text, done) }
            return
        } catch (_: Throwable) {
            // insecure origin or a rejected write: fall through to the legacy path
        }
    }
    legacyCopy(text, done)
}

private fun legacyCopy(text: String, onDone: (Boolean) -> Unit) {
    val area = document.createElement("textarea") as HTMLTextAreaElement
    try {
        area.value = text
        area.setAttribute("readonly", "")
        area.style.position = "fixed"
        area.style.top = "0"
        area.style.opacity = "0"
        document.body?.appendChild(area)
        area.select()
        onDone(document.asDynamic().execCommand("copy") as Boolean)
    } catch (_: Throwable) {
        onDone(false)
    } finally {
        area.remove()
    }
}

private const val PLAYER_KEY = "tictactoe-player-key"

/** A persistent secret that owns the player's name across sessions; travels only in the WS join query. */
private fun loadOrCreatePlayerKey(): String {
    localStorage.getItem(PLAYER_KEY)?.let { return it }
    val bytes = Uint8Array(16)
    window.asDynamic().crypto.getRandomValues(bytes)
    val key = (0 until bytes.length).joinToString("") { i -> (bytes[i].toInt() and 0xFF).toString(16).padStart(2, '0') }
    localStorage.setItem(PLAYER_KEY, key)
    return key
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
    private val resignButton = document.getElementById("resign") as HTMLButtonElement
    private val hintButton = document.getElementById("hint") as HTMLButtonElement
    private val turnBar = document.getElementById("turn-bar") as HTMLElement
    private val turnBarFill = document.getElementById("turn-bar-fill") as HTMLElement
    private val movesList = document.getElementById("moves") as HTMLElement
    private val nameInput = document.getElementById("name") as? HTMLInputElement
    private val setNameButton = document.getElementById("set-name") as? HTMLButtonElement

    private val params = URLSearchParams(window.location.search)
    private val room = params.get("room")
    private val size = params.get("size")
    private val win = params.get("win")
    private val bot = params.get("bot")
    private val turn = params.get("turn")
    private val clock = params.get("clock")
    private val wins = params.get("wins")
    private val spectate = params.get("spectate")

    // an explicit lang parameter wins; otherwise the browser's own preference decides
    private val lang = clientLang()
    private val preferences = Preferences(
        document.getElementById("sound") as HTMLButtonElement,
        document.getElementById("theme") as HTMLButtonElement,
        lang,
    )
    private val cues = Cues(preferences)
    private val boardRenderer = BoardRenderer(board, turnBar, lang) { x, y ->
        cues.unlock()
        send(ClientMessage.MakeTurn(x = x, y = y))
    }
    private val turnAlert = TurnAlert(lang)
    private val panels = SidePanels(lang, params.get("lang"))
    private val wsProtocol = if (window.location.protocol == "https:") "wss" else "ws"

    // the remembered name rides the join URL, so anonymous matchmaking can pair by rating
    private val storedName = localStorage.getItem(NAME_KEY)
    private val playerKey = loadOrCreatePlayerKey()
    private val query = joinQuery(
        mapOf(
            "room" to room,
            "size" to size,
            "win" to win,
            "bot" to bot,
            "turn" to turn,
            "clock" to clock,
            "wins" to wins,
            "spectate" to spectate,
            "name" to storedName,
            "key" to playerKey,
            "lang" to params.get("lang"),
        ),
    )
    private val wsUrl = "$wsProtocol://${window.location.host}/play" + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    private val tokenKey = "tictactoe-token" + (room?.let { ":$it" } ?: "")

    companion object {
        const val NAME_KEY = "tictactoe-name"
    }

    // answered before the game even started: the server closes the socket, so retrying cannot fix them
    private val fatalReasons = setOf(
        ErrorReason.ROOM_IS_FULL,
        ErrorReason.INVALID_ROOM_NAME,
        ErrorReason.INVALID_BOARD_SIZE,
        ErrorReason.INVALID_WIN_LENGTH,
        ErrorReason.INVALID_TURN_TIME,
        ErrorReason.INVALID_BOT_LEVEL,
        ErrorReason.INVALID_CLOCK,
        ErrorReason.INVALID_WINS,
    )

    private var socket: WebSocket? = null
    private var hintWorker: Worker? = null
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
        panels.mountReplay(host, replay.boardSize, replay.moves)

        val back = document.createElement("button") as HTMLButtonElement
        back.className = "secondary"
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

    /** The running move list beside the board; each cell's occupant names its mover. */
    private fun renderMoveLog() {
        val current = lastState ?: return
        movesList.innerHTML = ""
        current.moves.forEachIndexed { i, cell ->
            val li = document.createElement("li") as HTMLElement
            li.textContent = "${i + 1}. ${current.field[cell.y][cell.x]} ${cellNotation(cell.x, cell.y)}"
            movesList.appendChild(li)
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
        renderMoveLog()
        turnAlert.onState(previous, state, mySymbol)
        updateActionButtons(state)
        updateStatus(previous, state)
        boardRenderer.render(previous, state, mySymbol)
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
        updateResignButton(state)
        updateHintButton(state)
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
        // a bot never agrees to a draw: the offer only makes sense between humans
        val visible = !roundOver && mySymbol != null && state.connectionPlayers.size >= 2 && state.botPlayers.isEmpty()
        drawButton.style.display = if (visible) "block" else "none"
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

    private fun updateResignButton(state: GameState) {
        val roundOver = state.isRoundOver
        resignButton.style.display = if (!roundOver && mySymbol != null && state.connectionPlayers.size >= 2) "block" else "none"
    }

    private fun updateHintButton(state: GameState) {
        hintButton.style.display = if (hintAvailable(state, mySymbol)) "block" else "none"
    }

    /**
     * Runs the same search the hard bot plays, but inside a Web Worker so a deep
     * search on a big board cannot freeze the tab. The bundle re-runs in worker
     * mode (no window) and answers with the suggested cell.
     */
    private fun requestHint() {
        val state = lastState ?: return
        val me = mySymbol ?: return
        hintButton.disabled = true
        val worker = hintWorker ?: Worker("/static/app.js").also { hintWorker = it }
        worker.onmessage = { event ->
            hintButton.disabled = false
            val data = event.data.asDynamic()
            // a hint for a stale board is useless: only show it while the state is unchanged
            if (data != null && lastState === state) boardRenderer.showHint(data.x as Int, data.y as Int)
        }
        worker.onerror = {
            hintButton.disabled = false
        }
        worker.postMessage(
            json(
                "field" to state.field.map { row -> row.map { it?.name }.toTypedArray() }.toTypedArray(),
                "size" to state.field.size,
                "winLength" to state.winLength,
                "symbol" to me.name,
            ),
        )
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
        cues.play(previous, state, mySymbol)
        if ((previous == null || !previous.isRoundOver) && state.isRoundOver) panels.refreshLeaderboard()
    }

    private fun handleMessage(text: String) {
        when (val event = protocolJson.decodeFromString<ServerEvent>(text)) {
            is ServerEvent.Joined -> {
                mySymbol = event.symbol
                localStorage.setItem(tokenKey, event.token)
                showError("")
            }

            is ServerEvent.StateChanged -> {
                // the world moved on: a refusal of an earlier action is stale now
                showError("")
                pendingReconnectToken?.let { localStorage.setItem(tokenKey, it) }
                pendingReconnectToken = null
                // the server is the source of truth: cache the name it attributes to me
                val myName = mySymbol?.let { symbol -> event.state.playerNames[symbol] }
                if (myName != null) localStorage.setItem(NAME_KEY, myName)
                showState(event)
            }

            is ServerEvent.Error -> {
                pendingReconnectToken = null
                if (event.reason == ErrorReason.UNKNOWN_TOKEN) localStorage.removeItem(tokenKey)
                if (event.reason in fatalReasons) fatal = true
                showError(errorText(event.reason, lang))
            }

            is ServerEvent.Reaction -> showReaction(event.emoji)

            is ServerEvent.Chat -> appendChat(event)
        }
    }

    /** Chat lines are appended as text nodes, never injected as HTML. */
    private fun appendChat(event: ServerEvent.Chat) {
        val log = document.getElementById("chat-log") as? HTMLElement ?: return
        val line = document.createElement("p") as HTMLElement
        line.className = "chat-line"
        line.textContent = "${event.name ?: spectatorText(lang)}: ${event.text}"
        log.appendChild(line)
        while (log.childElementCount > 50) log.firstElementChild?.remove()
        log.asDynamic().scrollTop = log.asDynamic().scrollHeight
    }

    private fun installChat() {
        (document.getElementById("chat-send") as? HTMLElement)?.let { button ->
            button.onclick = { sendChatLine() }
        }
        (document.getElementById("chat-input") as? HTMLInputElement)?.let { input ->
            input.onkeydown = { event ->
                if (event.asDynamic().key == "Enter") sendChatLine()
            }
        }
    }

    private fun sendChatLine() {
        val input = document.getElementById("chat-input") as? HTMLInputElement ?: return
        val text = input.value
        if (text.isBlank()) return
        send(ClientMessage.Chat(text))
        input.value = ""
    }

    private fun connect() {
        showStatus(if (retry == 0) connectingText(lang) else reconnectingText(lang))
        socket = WebSocket(wsUrl)
        socket?.onopen = {
            retry = 0
            hideRetryButton()
            showError("")
            val stored = localStorage.getItem(tokenKey)
            if (stored != null) {
                pendingReconnectToken = stored
                send(ClientMessage.Reconnect(stored))
            }
            // the remembered name rides the join URL — no set_name needed here
        }
        socket?.onmessage = { event ->
            handleMessage(event.data.toString())
        }
        socket?.onclose = {
            stopOpponentLostPlate()
            stopCountdowns()
            turnAlert.stop()
            if (fatal) {
                // the join was rejected: the reason is in the error box, there is nothing to reconnect to
                hideRetryButton()
                showStatus("")
            } else if (retry >= MAX_AUTO_RETRIES) {
                showError(connectionErrorText(lang))
                showStatus("")
                showRetryButton()
            } else {
                showStatus(connectionLostText(lang))
                retry++
                window.setTimeout({ connect() }, retryDelayMs(retry).toInt())
            }
        }
        socket?.onerror = {
            showError(connectionErrorText(lang))
        }
    }

    private fun showRetryButton() {
        val host = document.getElementById("connection-actions") as? HTMLElement ?: return
        if (document.getElementById("retry-now") != null) return
        val button = document.createElement("button") as HTMLButtonElement
        button.id = "retry-now"
        button.className = "secondary"
        button.textContent = retryNowText(lang)
        button.onclick = {
            button.remove()
            retry = 0
            connect()
        }
        host.appendChild(button)
    }

    private fun hideRetryButton() {
        document.getElementById("retry-now")?.remove()
    }

    /** Enter in the leaderboard search opens that player's profile. */
    private fun installPlayerSearch() {
        document.getElementById("player-search")?.let { node ->
            val search = node as HTMLInputElement
            search.onkeydown = { event ->
                if (event.asDynamic().key == "Enter") {
                    val name = search.value.trim()
                    if (name.isNotEmpty()) panels.showProfile(name)
                }
            }
        }
    }

    fun start() {
        copyLink?.let { button ->
            button.onclick = {
                copyTextWithFeedback(
                    text = window.location.href,
                    button = button,
                    successText = copiedText(lang),
                    failureText = copyFailedText(lang),
                    restoreText = copyLinkText(lang),
                )
            }
        }

        rematchButton.onclick = { send(ClientMessage.RequestRematch) }
        drawButton.onclick = { send(ClientMessage.RequestDraw) }
        undoButton.onclick = { send(ClientMessage.RequestUndo) }
        resignButton.onclick = { send(ClientMessage.RequestResign) }
        hintButton.onclick = { requestHint() }

        document.querySelectorAll("#reactions button").asDynamic().forEach { node: dynamic ->
            (node as? HTMLButtonElement)?.let { button ->
                button.onclick = { send(ClientMessage.React(button.textContent ?: "")) }
            }
        }

        if (nameInput != null && setNameButton != null) {
            nameInput.value = localStorage.getItem(NAME_KEY) ?: ""
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

        installPlayerSearch()
        installChat()

        panels.refreshLeaderboard()
        window.setInterval({ panels.refreshLeaderboard() }, 30_000) // the table also moves while nobody finishes a round
        panels.refreshRooms()
        window.setInterval({ panels.refreshRooms() }, 5_000)

        turnAlert.install()

        val sharedReplay = replayFromFragment(window.location.hash.removePrefix("#"))
        if (sharedReplay != null) {
            showSharedReplay(sharedReplay) // the link carries the whole game: no connection needed
        } else {
            connect()
        }
    }
}

/** Tiny synthesized Web Audio cues: my move, the opponent's, wins and draws — never a game breaker. */
private class Cues(private val preferences: Preferences) {
    private var audioCtx: dynamic = null

    /** The first user gesture unlocks audio playback in a browser. */
    fun unlock() {
        if (audioCtx != null) return
        try {
            audioCtx = js("new (window.AudioContext || window.webkitAudioContext)()")
        } catch (ignored: Throwable) {
            audioCtx = null
        }
    }

    fun play(previous: GameState?, state: GameState, mySymbol: PlayerSymbol?) {
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

    private fun beep(freq: Double, durationMs: Int, type: String = "sine") {
        if (preferences.muted) return
        try {
            unlock()
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
}

/** The hidden-tab alert: a flashing tab title, a buzz and a system notification while it is your move. */
private class TurnAlert(private val lang: Lang) {
    private var timer: Int? = null
    private val baseTitle = window.document.title
    private var permissionAsked = false

    /** Notification permission needs one user gesture; the first click anywhere is as good as any. */
    fun install() {
        document.addEventListener("pointerdown", {
            if (permissionAsked) return@addEventListener
            permissionAsked = true
            try {
                window.asDynamic().Notification?.requestPermission()
            } catch (_: Throwable) {
            }
        })
        // a returning tab immediately gets its normal title back
        document.addEventListener("visibilitychange", {
            if (window.document.asDynamic().hidden as Boolean) return@addEventListener
            stop()
        })
    }

    /** The page is hidden and it is my move: flash the title; a fresh turn also buzzes and notifies. */
    fun onState(previous: GameState?, state: GameState, mySymbol: PlayerSymbol?) {
        val hidden = window.document.asDynamic().hidden as Boolean
        if (shouldAlertTurn(state, mySymbol) && hidden) {
            startFlash()
            if (isTurnTransition(previous, state)) {
                vibrate()
                notify()
            }
        } else {
            stop()
        }
    }

    /** The connection is gone: no turn is coming, the title goes back to normal. */
    fun stop() {
        timer?.let { window.clearInterval(it) }
        timer = null
        window.document.title = baseTitle
    }

    private fun startFlash() {
        if (timer != null) return
        val alert = turnAlertTitle(lang)
        var alerted = false
        window.document.title = "$alert $baseTitle"
        timer = window.setInterval({
            alerted = !alerted
            window.document.title = if (alerted) baseTitle else "$alert $baseTitle"
        }, 1_000)
    }

    private fun vibrate() {
        try {
            js("window.navigator.vibrate && window.navigator.vibrate(200)")
        } catch (_: Throwable) {
        }
    }

    private fun notify() {
        try {
            val notif = window.asDynamic().Notification ?: return
            if (notif.permission != "granted") return
            val title = turnAlertTitle(lang)
            val body = turnAlertBody(lang)
            js("new Notification(title, { body: body })")
        } catch (_: Throwable) {
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
        soundButton.setAttribute("aria-label", soundTitle(muted, lang))
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
        themeButton.setAttribute("aria-label", themeTitle(theme, lang))
        updateThemeColorMetas()
    }

    private fun updateThemeColorMetas() {
        document.getElementById("theme-color-light")
            ?.setAttribute("content", themeColor(theme, prefersLight = true))
        document.getElementById("theme-color-dark")
            ?.setAttribute("content", themeColor(theme, prefersLight = false))
    }

    private companion object {
        const val MUTED_KEY = "tictactoe-muted"
        const val THEME_KEY = "tictactoe-theme"
    }
}

/** The decorative side panels: the room list, the leaderboard, the profiles and their replays. */
private class SidePanels(private val lang: Lang, private val langParam: String?) {

    fun refreshLeaderboard() {
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
                            val series = room.seriesTarget as? Int
                            if (series != null) append(", ${seriesTargetText(series, lang)}")
                        }
                        li.textContent = "${room.name} — ${room.players}/2 · $details"
                        li.style.cursor = "pointer"
                        li.onclick = {
                            val langSuffix = langParam?.let { "&lang=${urlEncode(it)}" } ?: ""
                            window.location.href = "${window.location.pathname}?room=${urlEncode(room.name as String)}$langSuffix"
                        }
                        rows.appendChild(li)
                    }
                }
            }.catch { /* the room list is decorative */ }
        } catch (ignored: Throwable) {
            // decorative as well
        }
    }

    fun showProfile(name: String, page: Int = 0) {
        try {
            window.asDynamic().fetch("/player?name=${urlEncode(name)}&page=$page").then { response: dynamic ->
                val profileDiv = document.getElementById("profile") as HTMLElement
                if (response.status != 200) {
                    profileDiv.innerHTML = ""
                    val missing = document.createElement("p")
                    missing.textContent = profileNotFoundText(lang)
                    profileDiv.appendChild(missing)
                    return@then
                }
                response.json().then { data: dynamic ->
                    profileDiv.innerHTML = ""
                    val title = document.createElement("h3")
                    title.textContent = "${data.name as String} · #${data.rank as Int} · ${data.stats.rating as Int}"
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
                    mountRatingSparkline(data, profileDiv)
                    profileDiv.appendChild(ul)
                    profileDiv.appendChild(replayDiv)
                    mountHistoryPages(data, name, profileDiv)
                    mountHeadToHead(data, profileDiv)
                }
            }.catch { /* the profile is decorative */ }
        } catch (ignored: Throwable) {
            // decorative as well
        }
    }

    /** The rating curve over the history page's rated rounds, oldest to newest, drawn as an SVG polyline. */
    private fun mountRatingSparkline(data: dynamic, profileDiv: HTMLElement) {
        val history = (data.stats.history ?: js("[]")) as Array<dynamic>
        val ratings = history.mapNotNull { it.ratingAfter as? Int }.reversed()
        val points = ratingSparklinePoints(ratings)
        if (points.isEmpty()) return
        val svg = js("document.createElementNS('http://www.w3.org/2000/svg', 'svg')") as Element
        svg.setAttribute("viewBox", "0 0 100 30")
        svg.setAttribute("class", "sparkline")
        val polyline = js("document.createElementNS('http://www.w3.org/2000/svg', 'polyline')") as Element
        polyline.setAttribute("points", points.joinToString(" ") { "${it.first},${it.second}" })
        svg.appendChild(polyline)
        profileDiv.appendChild(svg)
    }

    /** The history pages over twenty records a page: two glyph buttons re-fetch the slice. */
    private fun mountHistoryPages(data: dynamic, name: String, profileDiv: HTMLElement) {
        val pages = (data.pages ?: js("1")) as Int
        if (pages <= 1) return
        val current = (data.page ?: js("0")) as Int
        val row = document.createElement("div") as HTMLElement
        val older = document.createElement("button") as HTMLButtonElement
        older.className = "secondary"
        older.textContent = "‹"
        older.disabled = current >= pages - 1
        older.onclick = { showProfile(name, current + 1) }
        val newer = document.createElement("button") as HTMLButtonElement
        newer.className = "secondary"
        newer.textContent = "›"
        newer.disabled = current <= 0
        newer.onclick = { showProfile(name, current - 1) }
        val label = document.createElement("span")
        label.textContent = " ${current + 1}/$pages "
        row.appendChild(older)
        row.appendChild(label)
        row.appendChild(newer)
        profileDiv.appendChild(row)
    }

    fun showReplay(record: dynamic, container: HTMLElement) {
        val boardSize = ((record.boardSize ?: js("3")) as Double).toInt()
        val moves = ((record.moves ?: js("[]")) as Array<dynamic>).map { m ->
            Cell((m.x as Double).toInt(), (m.y as Double).toInt())
        }
        container.innerHTML = ""
        mountReplay(container, boardSize, moves)
    }

    /** The stepper + mini board every replay view shares; a link button carries the whole game in the URL. */
    fun mountReplay(
        container: HTMLElement,
        boardSize: Int,
        moves: List<Cell>,
    ) {
        ReplayView(container, boardSize, moves, lang)
    }

    /** The running W/L/D tally per opponent, busiest matchups first. */
    private fun mountHeadToHead(data: dynamic, profileDiv: HTMLElement) {
        val opponents = data.stats.opponents ?: return
        val head = document.createElement("h3")
        head.textContent = headToHeadTitle(lang)
        val ul = document.createElement("ul") as HTMLElement
        val entries = js("Object.entries(opponents)")
        js("entries.sort(function (a, b) { return (b[1].wins + b[1].losses + b[1].draws) - (a[1].wins + a[1].losses + a[1].draws) })")
        (entries as Array<dynamic>).forEach { pair ->
            val li = document.createElement("li") as HTMLElement
            val tally = pair[1]
            li.textContent = "vs ${pair[0]} — ${tally.wins}:${tally.losses}:${tally.draws}"
            ul.appendChild(li)
        }
        profileDiv.appendChild(head)
        profileDiv.appendChild(ul)
    }

    private fun resultMark(result: String): String = when (result) {
        "WIN" -> "✓"
        "LOSS" -> "✗"
        else -> "="
    }
}

/** One mounted replay: a stepper, autoplay and arrow keys over a mini board. */
private class ReplayView(
    container: HTMLElement,
    private val boardSize: Int,
    private val moves: List<Cell>,
    private val lang: Lang,
) {
    private var index = moves.size // start at the final position
    private var playTimer: Int? = null

    private val prev = document.createElement("button") as HTMLButtonElement
    private val next = document.createElement("button") as HTMLButtonElement
    private val play = document.createElement("button") as HTMLButtonElement
    private val label = document.createElement("span")
    private val boardDiv = document.createElement("div") as HTMLElement

    init {
        prev.className = "secondary"
        next.className = "secondary"
        play.className = "secondary"
        prev.textContent = "‹"
        next.textContent = "›"
        play.textContent = "▶"
        play.title = autoplayText(lang)
        val share = document.createElement("button") as HTMLButtonElement
        share.className = "secondary"
        share.textContent = "🔗"
        share.title = copyLinkText(lang)
        val controls = document.createElement("div") as HTMLElement
        controls.className = "replay-controls"
        controls.appendChild(prev)
        controls.appendChild(label)
        controls.appendChild(next)
        controls.appendChild(play)
        controls.appendChild(share)

        boardDiv.style.display = "grid"
        boardDiv.style.setProperty("grid-template-columns", "repeat($boardSize, 26px)")

        prev.onclick = {
            stopPlaying()
            step(-1)
        }
        next.onclick = {
            stopPlaying()
            step(1)
        }
        play.onclick = { togglePlay() }
        share.onclick = {
            val fragment = replayToFragment(SharedReplay(boardSize, boardSize, moves))
            val url = "${window.location.origin}${window.location.pathname}${window.location.search}#$fragment"
            copyTextWithFeedback(url, share, "✓", copyFailedText(lang), "🔗")
        }

        container.appendChild(controls)
        container.appendChild(boardDiv)
        render()
        captureArrowKeys()
    }

    /** Moves the playhead and stops the autoplay once the final position is reached. */
    private fun step(delta: Int) {
        index = (index + delta).coerceIn(0, moves.size)
        if (index == moves.size) stopPlaying()
        render()
    }

    private fun togglePlay() {
        if (playTimer != null) {
            stopPlaying()
        } else {
            play.textContent = "⏸"
            playTimer = window.setInterval({ step(1) }, 700)
        }
    }

    private fun stopPlaying() {
        playTimer?.let { window.clearInterval(it) }
        playTimer = null
        play.textContent = "▶"
    }

    private fun render() {
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

    /** The last mounted replay owns the arrow keys; typing into an input never moves the playhead. */
    private fun captureArrowKeys() {
        val handler: (Event) -> Unit = { event ->
            if ((event.target as? HTMLElement)?.tagName != "INPUT") {
                when (event.asDynamic().key) {
                    "ArrowLeft" -> {
                        stopPlaying()
                        step(-1)
                    }

                    "ArrowRight" -> {
                        stopPlaying()
                        step(1)
                    }
                }
            }
        }
        activeKeyHandler?.let { document.removeEventListener("keydown", it) }
        activeKeyHandler = handler
        document.addEventListener("keydown", handler)
    }

    private companion object {
        var activeKeyHandler: ((Event) -> Unit)? = null
    }
}
