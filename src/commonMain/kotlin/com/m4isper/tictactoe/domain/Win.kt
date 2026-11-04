package com.m4isper.tictactoe.domain

data class Win(
    val player: PlayerSymbol,
    val cells: List<Cell>,
)
