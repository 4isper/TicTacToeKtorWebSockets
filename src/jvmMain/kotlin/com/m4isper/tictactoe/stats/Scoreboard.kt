package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.Elo
import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MatchRecord(
    val epochMs: Long,
    val opponent: String? = null,
    val result: RoundResult,
    val moves: List<Cell> = emptyList(),
    val boardSize: Int = 3,
    val ratingAfter: Int? = null,
)

@Serializable
data class PlayerStats(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val rating: Int = Elo.DEFAULT,
    val history: List<MatchRecord> = emptyList(),
)

@Serializable
data class LeaderboardEntry(
    val name: String,
    val stats: PlayerStats,
)

@Serializable
data class PlayerProfile(
    val name: String,
    val stats: PlayerStats,
)

@Serializable
data class LeaderboardResponse(val entries: List<LeaderboardEntry> = emptyList())

@Serializable
private data class StoredScores(val players: Map<String, PlayerStats> = emptyMap())

enum class RoundResult { WIN, LOSS, DRAW }

interface ScoreStore {
    fun addResult(
        name: String,
        result: RoundResult,
        opponent: String? = null,
        atMs: Long = System.currentTimeMillis(),
        moves: List<Cell> = emptyList(),
        boardSize: Int = 3,
        rating: Int? = null,
        ratingAfter: Int? = null,
    )

    fun loadAll(): Map<String, PlayerStats>
}

private const val HISTORY_LIMIT = 20

/** The shared result math: bump the counter, adopt the rating, prepend the record, cap the history. */
internal fun statsAfter(
    current: PlayerStats?,
    result: RoundResult,
    opponent: String?,
    atMs: Long,
    moves: List<Cell>,
    boardSize: Int,
    rating: Int?,
    ratingAfter: Int?,
): PlayerStats {
    val base = current ?: PlayerStats()
    val counted = when (result) {
        RoundResult.WIN -> base.copy(wins = base.wins + 1)
        RoundResult.LOSS -> base.copy(losses = base.losses + 1)
        RoundResult.DRAW -> base.copy(draws = base.draws + 1)
    }.copy(rating = rating ?: base.rating)
    val record = MatchRecord(
        epochMs = atMs,
        opponent = opponent,
        result = result,
        moves = moves,
        boardSize = boardSize,
        ratingAfter = ratingAfter,
    )
    return counted.copy(history = (listOf(record) + base.history).take(HISTORY_LIMIT))
}

/** JSON-file store with an atomic write (temp file + rename) so a crash mid-write keeps the old data. */
class JsonScoreStore(private val file: File) : ScoreStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()
    private val players: MutableMap<String, PlayerStats>

    init {
        players = synchronized(lock) {
            if (file.exists()) {
                runCatching { json.decodeFromString<StoredScores>(file.readText()).players }
                    .getOrElse { emptyMap() }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        }
    }

    override fun addResult(
        name: String,
        result: RoundResult,
        opponent: String?,
        atMs: Long,
        moves: List<Cell>,
        boardSize: Int,
        rating: Int?,
        ratingAfter: Int?,
    ): Unit = synchronized(lock) {
        players[name] = statsAfter(players[name], result, opponent, atMs, moves, boardSize, rating, ratingAfter)
        persistLocked()
    }

    override fun loadAll(): Map<String, PlayerStats> = synchronized(lock) { players.toMap() }

    private fun persistLocked() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(json.encodeToString(StoredScores(players)))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

/** Keeps per-name round results; unnamed players are invisible to the leaderboard. */
class Scoreboard(private val store: ScoreStore) {

    fun recordRound(
        playerNames: Map<PlayerSymbol, String>,
        winner: PlayerSymbol?,
        moves: List<Cell> = emptyList(),
        boardSize: Int = 3,
    ) {
        // both ratings are read before either is written, so the exchange is fair
        val all = store.loadAll()
        val xName = playerNames[PlayerSymbol.X]
        val oName = playerNames[PlayerSymbol.O]
        val xRating = xName?.let { all[it]?.rating } ?: Elo.DEFAULT
        val oRating = oName?.let { all[it]?.rating } ?: Elo.DEFAULT
        val (xScore, oScore) = when (winner) {
            PlayerSymbol.X -> 1.0 to 0.0
            PlayerSymbol.O -> 0.0 to 1.0
            null -> 0.5 to 0.5
        }
        val xNext = Elo.next(xRating, oRating, xScore)
        val oNext = Elo.next(oRating, xRating, oScore)

        if (winner == null) {
            val named = playerNames.values.toList()
            named.forEach { name ->
                val next = if (name == xName) xNext else oNext
                store.addResult(
                    name,
                    RoundResult.DRAW,
                    opponent = named.firstOrNull { it != name },
                    moves = moves,
                    boardSize = boardSize,
                    rating = next,
                    ratingAfter = next,
                )
            }
            return
        }
        val winnerName = playerNames[winner]
        val loserName = playerNames[winner.other]
        val winnerNext = if (winner == PlayerSymbol.X) xNext else oNext
        val loserNext = if (winner == PlayerSymbol.X) oNext else xNext
        winnerName?.let { store.addResult(it, RoundResult.WIN, loserName, moves = moves, boardSize = boardSize, rating = winnerNext, ratingAfter = winnerNext) }
        loserName?.let { store.addResult(it, RoundResult.LOSS, winnerName, moves = moves, boardSize = boardSize, rating = loserNext, ratingAfter = loserNext) }
    }

    fun profile(name: String?): PlayerProfile? {
        if (name.isNullOrBlank()) return null
        val stats = store.loadAll()[name] ?: return null
        return PlayerProfile(name, stats)
    }

    fun top(limit: Int = 10): List<LeaderboardEntry> = store.loadAll()
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, PlayerStats>> { it.value.rating }
                .thenByDescending { it.value.wins }
                .thenBy { it.key },
        )
        .take(limit)
        .map { LeaderboardEntry(it.key, it.value) }
}
