package com.m4isper.tictactoe.protocol

import com.m4isper.tictactoe.domain.ErrorReason
import com.m4isper.tictactoe.domain.PlayerSymbol
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
    fun `undo request round-trips`() {
        val json = protocolJson.encodeToString<ClientMessage>(ClientMessage.RequestUndo)
        assertEquals("""{"type":"request_undo"}""", json)
        assertEquals(ClientMessage.RequestUndo, protocolJson.decodeFromString(json))
    }

    @Test
    fun `set name round-trips`() {
        val json = protocolJson.encodeToString<ClientMessage>(ClientMessage.SetName("Дима"))
        assertEquals("""{"type":"set_name","name":"Дима"}""", json)
        assertEquals(ClientMessage.SetName("Дима"), protocolJson.decodeFromString(json))
    }

    @Test
    fun `chat messages round-trip with their author`() {
        val request = protocolJson.encodeToString<ClientMessage>(ClientMessage.Chat("привет"))
        assertEquals("""{"type":"chat","text":"привет"}""", request)
        assertEquals(ClientMessage.Chat("привет"), protocolJson.decodeFromString(request))

        val event = protocolJson.encodeToString<ServerEvent>(ServerEvent.Chat(PlayerSymbol.X, "Аня", "привет"))
        assertEquals("""{"type":"chat","from":"X","name":"Аня","text":"привет"}""", event)
        assertEquals(ServerEvent.Chat(PlayerSymbol.X, "Аня", "привет"), protocolJson.decodeFromString(event))

        val anonymous = protocolJson.encodeToString<ServerEvent>(ServerEvent.Chat(null, null, "hi"))
        assertEquals("""{"type":"chat","from":null,"name":null,"text":"hi"}""", anonymous)
    }
}
