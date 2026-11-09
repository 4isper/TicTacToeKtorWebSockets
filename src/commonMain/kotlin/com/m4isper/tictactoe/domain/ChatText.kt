package com.m4isper.tictactoe.domain

const val MAX_CHAT_LENGTH = 200

/** Chat text is untrusted input: flatten line breaks, trim, reject blank or oversized lines. */
fun normalizeChatText(raw: String): String? = raw
    .replace('\r', ' ')
    .replace('\n', ' ')
    .trim()
    .takeIf { it.isNotEmpty() && it.length <= MAX_CHAT_LENGTH }
