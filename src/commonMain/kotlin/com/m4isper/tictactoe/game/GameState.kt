package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val playerAtTurn: PlayerSymbol? = PlayerSymbol.X,
    val field: List<List<PlayerSymbol?>> = emptyField(),
    val winningPlayer: PlayerSymbol? = null,
    val winningCells: List<Cell> = emptyList(),
    val isBoardFull: Boolean = false,
    val connectionPlayers: List<PlayerSymbol> = emptyList(),
    /** Players whose connection dropped but whose slot is held for the reconnect grace. */
    val awayPlayers: List<PlayerSymbol> = emptyList(),
    val score: Map<PlayerSymbol, Int> = mapOf(PlayerSymbol.X to 0, PlayerSymbol.O to 0),
    val roundRestartInMs: Long? = null,
    val rematchPlayers: List<PlayerSymbol> = emptyList(),
    val playerNames: Map<PlayerSymbol, String> = emptyMap(),
    val turnRemainingMs: Long? = null,
    val drawPlayers: List<PlayerSymbol> = emptyList(),
    val isDrawAgreed: Boolean = false,
    val undoOfferBy: PlayerSymbol? = null,
    val clocks: Map<PlayerSymbol, Long> = emptyMap(),
) {
    /** Nothing left to play: won, drawn out, or given up by agreement. */
    val isRoundOver: Boolean
        get() = winningPlayer != null || isBoardFull || isDrawAgreed

    companion object {
        fun emptyField(size: Int = GameRules.DEFAULT_BOARD_SIZE): List<List<PlayerSymbol?>> = GameRules.emptyField(size)
    }
}
