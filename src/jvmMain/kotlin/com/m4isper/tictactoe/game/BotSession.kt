package com.m4isper.tictactoe.game

import io.ktor.websocket.*
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach

/** A black-hole session for the bot: it holds a player slot and swallows broadcasts. */
internal object BotSession : WebSocketSession {
    override val coroutineContext = SupervisorJob()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming: ReceiveChannel<Frame> = Channel()
    override val outgoing: SendChannel<Frame> = Channel(Channel.CONFLATED)
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override suspend fun flush() {}
    override fun terminate() {}
}
