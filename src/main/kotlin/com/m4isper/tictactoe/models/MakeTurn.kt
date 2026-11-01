package com.m4isper.tictactoe.models

import kotlinx.serialization.Serializable

@Serializable
data class MakeTurn(val x: Int, val y: Int)
