package com.m4isper.tictactoe.models

import kotlinx.serialization.Serializable

@Serializable
data class GameState(
    val playerAtTurn: Char? = 'X',
    val field: List<List<Char?>> = emptyField(),
    val winningPlayer: Char? = null,
    val isBoardFull: Boolean = false,
    val connectionPlayers: List<Char> = emptyList()
){
    companion object{
        fun emptyField(): List<List<Char?>>{
            return List(3) { List(3) { null } }
        }
    }
}
