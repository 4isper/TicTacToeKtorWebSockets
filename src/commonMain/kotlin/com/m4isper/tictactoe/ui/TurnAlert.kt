package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState

/** The player should be pulled back to a hidden tab: it is their move and the round is still running. */
fun shouldAlertTurn(state: GameState, mySymbol: PlayerSymbol?): Boolean = mySymbol != null &&
    state.connectionPlayers.size >= 2 &&
    !state.isRoundOver &&
    state.playerAtTurn == mySymbol

/** The flashing tab title while it is your move and the page is hidden. */
fun turnAlertTitle(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Ваш ход!"
    Lang.EN -> "Your move!"
}

/** The system notification body that rides with the flashing title. */
fun turnAlertBody(lang: Lang = Lang.RU): String = when (lang) {
    Lang.RU -> "Соперник сделал ход"
    Lang.EN -> "The opponent moved"
}
