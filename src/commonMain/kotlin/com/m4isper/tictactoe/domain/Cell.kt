package com.m4isper.tictactoe.domain

import kotlinx.serialization.Serializable

@Serializable
data class Cell(
    val x: Int,
    val y: Int,
)
