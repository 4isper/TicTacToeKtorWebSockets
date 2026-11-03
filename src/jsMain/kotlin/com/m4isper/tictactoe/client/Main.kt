package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.protocol.ClientMessage
import com.m4isper.tictactoe.protocol.ServerEvent
import com.m4isper.tictactoe.protocol.protocolJson
import com.m4isper.tictactoe.ui.scoreText
import com.m4isper.tictactoe.ui.statusText
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.url.URLSearchParams

fun main() {
    val status = document.getElementById("status") as HTMLElement
    val board = document.getElementById("board") as HTMLElement
    val errorBox = document.getElementById("error") as HTMLElement
    val scoreDiv = document.getElementById("score") as HTMLElement
    val copyLink = document.getElementById("copy-link") as? HTMLButtonElement

    val room = URLSearchParams(window.location.search).get("room")
    val wsProtocol = if (window.location.protocol == "https:") "wss" else "ws"
    val wsUrl = "$wsProtocol://${window.location.host}/play" + (room?.let { "?room=$it" } ?: "")
    val tokenKey = "tictactoe-token" + (room?.let { ":$it" } ?: "")

    var socket: WebSocket? = null
    var mySymbol: PlayerSymbol? = null
    var pendingReconnectToken: String? = null
    var retry = 0

    fun send(message: ClientMessage) {
        socket?.send(protocolJson.encodeToString<ClientMessage>(message))
    }

    fun showError(message: String) {
        errorBox.textContent = message
    }

    fun showStatus(text: String) {
        status.textContent = text
    }

    fun showState(event: ServerEvent.StateChanged) {
        val state = event.state
        showStatus(statusText(state, mySymbol))
        scoreDiv.textContent = scoreText(state)

        board.innerHTML = ""
        state.field.forEachIndexed { y, row ->
            val rowDiv = document.createElement("div")
            rowDiv.className = "row"
            row.forEachIndexed { x, cell ->
                val cellButton = document.createElement("button") as HTMLButtonElement
                cellButton.className = "cell"
                cellButton.textContent = when (cell) {
                    PlayerSymbol.X -> "X"
                    PlayerSymbol.O -> "O"
                    null -> ""
                }
                cellButton.disabled =
                    cell != null || mySymbol == null || state.connectionPlayers.size < 2 || state.playerAtTurn != mySymbol
                cellButton.onclick = { send(ClientMessage.MakeTurn(x = x, y = y)) }
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
                if (event.reason == "unknown token") localStorage.removeItem(tokenKey)
                showError(event.reason)
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
            button.textContent = "Скопировано!"
            window.setTimeout({ button.textContent = "Скопировать ссылку" }, 2000)
        }
    }

    connect()
}
