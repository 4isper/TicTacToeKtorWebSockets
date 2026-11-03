package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
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
)

@Serializable
data class PlayerStats(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
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
    )

    fun loadAll(): Map<String, PlayerStats>
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
    ): Unit = synchronized(lock) {
        val current = players[name] ?: PlayerStats()
        val stats = when (result) {
            RoundResult.WIN -> current.copy(wins = current.wins + 1)
            RoundResult.LOSS -> current.copy(losses = current.losses + 1)
            RoundResult.DRAW -> current.copy(draws = current.draws + 1)
        }
        val record = MatchRecord(epochMs = atMs, opponent = opponent, result = result, moves = moves, boardSize = boardSize)
        players[name] = stats.copy(history = (listOf(record) + stats.history).take(HISTORY_LIMIT))
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

    private companion object {
        const val HISTORY_LIMIT = 20
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
        if (winner == null) {
            val named = playerNames.values.toList()
            named.forEach { name ->
                store.addResult(
                    name,
                    RoundResult.DRAW,
                    opponent = named.filter { it != name }.firstOrNull(),
                    moves = moves,
                    boardSize = boardSize,
                )
            }
            return
        }
        val winnerName = playerNames[winner]
        val loserName = playerNames[winner.other]
        winnerName?.let { store.addResult(it, RoundResult.WIN, loserName, moves = moves, boardSize = boardSize) }
        loserName?.let { store.addResult(it, RoundResult.LOSS, winnerName, moves = moves, boardSize = boardSize) }
    }

    fun profile(name: String?): PlayerProfile? {
        if (name.isNullOrBlank()) return null
        val stats = store.loadAll()[name] ?: return null
        return PlayerProfile(name, stats)
    }

    fun top(limit: Int = 10): List<LeaderboardEntry> = store.loadAll()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, PlayerStats>> { it.value.wins }.thenBy { it.key })
        .take(limit)
        .map { LeaderboardEntry(it.key, it.value) }
}
