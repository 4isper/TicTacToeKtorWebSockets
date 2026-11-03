package com.m4isper.tictactoe.protocol

import com.m4isper.tictactoe.domain.ErrorReason
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolTest {

    @Test
    fun `error keeps the wire string`() {
        val json = protocolJson.encodeToString<ServerEvent>(ServerEvent.Error(ErrorReason.CELL_OCCUPIED))
        assertEquals("""{"type":"error","reason":"cell occupied"}""", json)
    }

    @Test
    fun `error reason parses from the wire string`() {
        val event = protocolJson.decodeFromString<ServerEvent>("""{"type":"error","reason":"stale session"}""")
        assertEquals(ServerEvent.Error(ErrorReason.STALE_SESSION), event)
    }

    @Test
    fun `rematch request round-trips`() {
        val json = protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestRematch)
        assertEquals("""{"type":"request_rematch"}""", json)
        assertEquals(ClientMessage.RequestRematch, protocolJson.decodeFromString(json))
    }

    @Test
    fun `set name round-trips`() {
        val json = protocolJson.encodeToString<ClientMessage>(ClientMessage.SetName("Дима"))
        assertEquals("""{"type":"set_name","name":"Дима"}""", json)
        assertEquals(ClientMessage.SetName("Дима"), protocolJson.decodeFromString(json))
    }
}
