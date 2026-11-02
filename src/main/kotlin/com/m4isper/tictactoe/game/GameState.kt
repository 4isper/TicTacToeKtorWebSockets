package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val playerAtTurn: PlayerSymbol? = PlayerSymbol.X,
    val field: List<List<PlayerSymbol?>> = emptyField(),
    val winningPlayer: PlayerSymbol? = null,
    val isBoardFull: Boolean = false,
    val connectionPlayers: List<PlayerSymbol> = emptyList(),
    val score: Map<PlayerSymbol, Int> = mapOf(PlayerSymbol.X to 0, PlayerSymbol.O to 0),
) {
    companion object {
        fun emptyField(size: Int = GameRules.DEFAULT_BOARD_SIZE): List<List<PlayerSymbol?>> = GameRules.emptyField(size)
    }
}
