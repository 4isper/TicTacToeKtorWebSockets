package com.m4isper.tictactoe.ui

/** The URL parameters a join link may carry, in the order the client forwards them to /play. */
private val JOIN_PARAM_NAMES = listOf("room", "size", "win", "bot", "turn", "clock", "wins", "spectate", "name", "lang")

private const val HEX = "0123456789ABCDEF"

/** Percent-encode one query value on every target; leaves only RFC 3986 unreserved bytes alone. */
internal fun urlEncode(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val unreserved = code in 'A'.code..'Z'.code ||
            code in 'a'.code..'z'.code ||
            code in '0'.code..'9'.code ||
            code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
        if (unreserved) {
            append(code.toChar())
        } else {
            append('%')
            append(HEX[code ushr 4])
            append(HEX[code and 0xF])
        }
    }
}

/**
 * The query string the WebSocket join re-sends: known parameters only, blank values dropped —
 * a GET form submits every field it renders, and a blank one would fail the server's integer
 * validation instead of falling back to the default.
 */
fun joinQuery(params: Map<String, String?>): String = JOIN_PARAM_NAMES
    .mapNotNull { name -> params[name]?.takeIf { it.isNotBlank() }?.let { "$name=${urlEncode(it)}" } }
    .joinToString("&")
