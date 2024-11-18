package com.example.models

import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

class TicTacToeGame {
    private val state = MutableStateFlow(GameState())
    
    private val playerSocket = ConcurrentHashMap<Char, WebSocketSession>()

    private val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}