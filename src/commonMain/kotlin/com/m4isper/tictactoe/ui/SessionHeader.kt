package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState

/** Who is looking at the page right now. */
enum class SessionRole {
    CONNECTING,
    PLAYER,
    SPECTATOR,
}

/** The two lines above the board: who is playing and by which rules. */
data class SessionHeader(
    val role: SessionRole,
    val identity: String,
    val rules: String,
)

/** The single place that decides the role, the identity line and the visible rule line. */
fun sessionHeader(
    state: GameState,
    mySymbol: PlayerSymbol?,
    joined: Boolean,
    lang: Lang = Lang.RU,
): SessionHeader = when {
    !joined -> SessionHeader(SessionRole.CONNECTING, "", "")
    mySymbol == null -> SessionHeader(SessionRole.SPECTATOR, spectatorIdentity(lang), rulesText(state, lang))
    else -> SessionHeader(SessionRole.PLAYER, playerIdentity(state, mySymbol, lang), rulesText(state, lang))
}

private fun playerIdentity(state: GameState, mySymbol: PlayerSymbol, lang: Lang): String {
    val opponent = mySymbol.other
    return "${youLabel(lang)} ${sideLabel(state, mySymbol)} · ${opponentLabel(lang)} ${sideLabel(state, opponent)}"
}

private fun sideLabel(state: GameState, symbol: PlayerSymbol): String = state.playerNames[symbol]?.let { "$it ($symbol)" } ?: symbol.toString()

private fun rulesText(state: GameState, lang: Lang): String = "${state.field.size}×${state.field.size} · ${winLengthText(state.winLength, lang)}"

private fun spectatorIdentity(lang: Lang): String = "${spectatorText(lang)} · ${watchingText(lang)}"

private fun youLabel(lang: Lang): String = when (lang) {
    Lang.RU -> "Вы:"
    Lang.EN -> "You:"
}

private fun opponentLabel(lang: Lang): String = when (lang) {
    Lang.RU -> "Соперник:"
    Lang.EN -> "Opponent:"
}

private fun watchingText(lang: Lang): String = when (lang) {
    Lang.RU -> "вы смотрите партию"
    Lang.EN -> "you are watching"
}
