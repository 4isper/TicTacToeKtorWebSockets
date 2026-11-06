package com.m4isper.tictactoe.ui

/** The rating curve for a profile: oldest to newest, in [width]×[height] coordinates ready for an SVG polyline. */
fun ratingSparklinePoints(
    ratings: List<Int>,
    width: Double = 100.0,
    height: Double = 30.0,
): List<Pair<Double, Double>> {
    if (ratings.size < 2) return emptyList()
    val lowest = ratings.min()
    val span = (ratings.max() - lowest).coerceAtLeast(1)
    return ratings.mapIndexed { i, rating ->
        val x = width * i / (ratings.size - 1)
        val y = height - height * (rating - lowest) / span
        x to y
    }
}
