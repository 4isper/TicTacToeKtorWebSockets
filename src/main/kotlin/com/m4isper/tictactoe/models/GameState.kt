package com.m4isper.tictactoe.models

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
) {
    companion object {
        fun emptyField(): List<List<PlayerSymbol?>> = GameRules.emptyField()
    }
}
