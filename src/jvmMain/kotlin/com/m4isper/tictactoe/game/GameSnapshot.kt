package com.m4isper.tictactoe.game

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable

/** The durable essence of a game: enough to resume a named room after a server restart. */
@Serializable
data class GameSnapshot(
    val state: GameState,
    val roundStarter: PlayerSymbol,
    val moves: List<Cell>,
    val clockX: Long,
    val clockO: Long,
)
