package com.m4isper.tictactoe.protocol

/**
 * Guards the WebSocket handshake against cross-site hijacking.
 * Non-browser clients send no Origin and stay allowed; a browser must come from
 * this very host (or from an explicitly configured origin behind a proxy).
 */
fun isOriginAllowed(
    origin: String?,
    expectedHost: String,
    scheme: String,
    extraAllowed: List<String> = emptyList(),
): Boolean {
    if (origin.isNullOrBlank()) return true
    if ("*" in extraAllowed) return true
    if (origin in extraAllowed) return true
    return origin.equals("$scheme://$expectedHost", ignoreCase = true)
}
