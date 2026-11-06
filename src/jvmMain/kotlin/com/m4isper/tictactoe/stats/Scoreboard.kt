package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.Elo
import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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

/** The running W/L/D tally against one named opponent. */
@Serializable
data class HeadToHead(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
) {
    val games: Int get() = wins + losses + draws
}

@Serializable
data class PlayerStats(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val rating: Int = Elo.DEFAULT,
    /** Rated rounds so far — the Elo step cools down as this grows. */
    val ratedGames: Int = 0,
    val history: List<MatchRecord> = emptyList(),
    val opponents: Map<String, HeadToHead> = emptyMap(),
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
    /** Which slice of the history this profile carries; [PAGINATION] records per page. */
    val page: Int = 0,
    val pages: Int = 1,
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

private const val HISTORY_LIMIT = 200

/** How many history records one profile page carries; the wire keeps up to [HISTORY_LIMIT]. */
const val PAGINATION = 20

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
        // a rated round is the one whose rating moved — both sides were named
        .copy(ratedGames = if (rating != null) base.ratedGames + 1 else base.ratedGames)
    val record = MatchRecord(
        epochMs = atMs,
        opponent = opponent,
        result = result,
        moves = moves,
        boardSize = boardSize,
        ratingAfter = ratingAfter,
    )
    return counted.copy(
        history = (listOf(record) + base.history).take(HISTORY_LIMIT),
        opponents = base.opponents.bump(opponent, result),
    )
}

/** A named opponent's tally moves with the result; practice rounds against bots count nowhere. */
private fun Map<String, HeadToHead>.bump(opponent: String?, result: RoundResult): Map<String, HeadToHead> {
    if (opponent == null) return this
    val tally = this[opponent] ?: HeadToHead()
    val updated = when (result) {
        RoundResult.WIN -> tally.copy(wins = tally.wins + 1)
        RoundResult.LOSS -> tally.copy(losses = tally.losses + 1)
        RoundResult.DRAW -> tally.copy(draws = tally.draws + 1)
    }
    return this + (opponent to updated)
}

/** JSON-file store with an atomic write (temp file + rename) so a crash mid-write keeps the old data. */
class JsonScoreStore(private val file: File) : ScoreStore {
    private val logger = LoggerFactory.getLogger(JsonScoreStore::class.java)
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
                    .getOrElse { error ->
                        // The first write replaces the file, so keep the unreadable
                        // original instead of silently losing the whole history.
                        val backup = File(file.parentFile, "${file.name}.corrupt")
                        runCatching { file.copyTo(backup, overwrite = true) }
                            .onFailure { logger.error("Could not back up the corrupt scoreboard {}", file, it) }
                        logger.warn("Scoreboard {} is corrupt; starting empty, original kept at {}", file, backup, error)
                        emptyMap()
                    }
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
    // Reading both ratings and writing both results is one transaction. Without a
    // single writer, two concurrent rounds read the same ratings and the second
    // overwrites the first one's Elo update. Reads take the same lock so the
    // leaderboard never sees half a round.
    private val lock = Any()

    fun recordRound(
        playerNames: Map<PlayerSymbol, String>,
        winner: PlayerSymbol?,
        moves: List<Cell> = emptyList(),
        boardSize: Int = 3,
    ): Unit = synchronized(lock) {
        val all = store.loadAll()
        val xName = playerNames[PlayerSymbol.X]
        val oName = playerNames[PlayerSymbol.O]
        val (xNext, oNext) = nextRatings(all, xName, oName, winner)

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
        } else {
            val winnerName = playerNames[winner]
            val loserName = playerNames[winner.other]
            val winnerNext = if (winner == PlayerSymbol.X) xNext else oNext
            val loserNext = if (winner == PlayerSymbol.X) oNext else xNext
            winnerName?.let { store.addResult(it, RoundResult.WIN, loserName, moves = moves, boardSize = boardSize, rating = winnerNext, ratingAfter = winnerNext) }
            loserName?.let { store.addResult(it, RoundResult.LOSS, winnerName, moves = moves, boardSize = boardSize, rating = loserNext, ratingAfter = loserNext) }
        }
    }

    /**
     * The next rating for each side, read before either is written so the exchange is fair.
     * A round against a bot (an unnamed side) is practice: the ratings come back null and
     * [statsAfter] keeps the players where they were.
     */
    private fun nextRatings(
        all: Map<String, PlayerStats>,
        xName: String?,
        oName: String?,
        winner: PlayerSymbol?,
    ): Pair<Int?, Int?> {
        if (xName == null || oName == null) return null to null
        val xRating = all[xName]?.rating ?: Elo.DEFAULT
        val oRating = all[oName]?.rating ?: Elo.DEFAULT
        val (xScore, oScore) = when (winner) {
            PlayerSymbol.X -> 1.0 to 0.0
            PlayerSymbol.O -> 0.0 to 1.0
            null -> 0.5 to 0.5
        }
        // each side's step follows its own rated-game count: a newcomer climbs faster
        val xNext = Elo.next(xRating, oRating, xScore, k = Elo.kFor(all[xName]?.ratedGames ?: 0))
        val oNext = Elo.next(oRating, xRating, oScore, k = Elo.kFor(all[oName]?.ratedGames ?: 0))
        return xNext to oNext
    }

    /** The profile with its history sliced to [PAGINATION] records; a page outside the range clamps. */
    fun profile(name: String?, page: Int = 0): PlayerProfile? = synchronized(lock) {
        if (name.isNullOrBlank()) {
            null
        } else {
            val stats = store.loadAll()[name] ?: return null
            val pages = maxOf(1, (stats.history.size + PAGINATION - 1) / PAGINATION)
            val safePage = page.coerceIn(0, pages - 1)
            val sliced = stats.copy(history = stats.history.drop(safePage * PAGINATION).take(PAGINATION))
            PlayerProfile(name, sliced, safePage, pages)
        }
    }

    /** The player's current rating, or null when unknown — anonymous matchmaking matches by it. */
    fun ratingOf(name: String): Int? = synchronized(lock) { store.loadAll()[name]?.rating }

    fun top(limit: Int = 10): List<LeaderboardEntry> = synchronized(lock) {
        store.loadAll()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, PlayerStats>> { it.value.rating }
                    .thenByDescending { it.value.wins }
                    .thenBy { it.key },
            )
            .take(limit)
            .map { LeaderboardEntry(it.key, it.value) }
    }
}
