package com.m4isper.tictactoe.ui

/** The URL parameters a join link may carry, in the order the client forwards them to /play. */
private val JOIN_PARAM_NAMES = listOf("room", "size", "win", "bot", "turn", "clock", "lang")

/**
 * The query string the WebSocket join re-sends: known parameters only, blank values dropped —
 * a GET form submits every field it renders, and a blank one would fail the server's integer
 * validation instead of falling back to the default.
 */
fun joinQuery(params: Map<String, String?>): String = JOIN_PARAM_NAMES
    .mapNotNull { name -> params[name]?.takeIf { it.isNotBlank() }?.let { "$name=$it" } }
    .joinToString("&")
