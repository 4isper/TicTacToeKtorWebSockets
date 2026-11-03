package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.PlayerSymbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PlayerStats(
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
)

data class LeaderboardEntry(
    val name: String,
    val stats: PlayerStats,
)

@Serializable
private data class StoredScores(val players: Map<String, PlayerStats> = emptyMap())

enum class RoundResult { WIN, LOSS, DRAW }

interface ScoreStore {
    fun addResult(name: String, result: RoundResult)
    fun loadAll(): Map<String, PlayerStats>
}

/** JSON-file store with an atomic write (temp file + rename) so a crash mid-write keeps the old data. */
class JsonScoreStore(private val file: File) : ScoreStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
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

    override fun addResult(name: String, result: RoundResult): Unit = synchronized(lock) {
        val current = players[name] ?: PlayerStats()
        players[name] = when (result) {
            RoundResult.WIN -> current.copy(wins = current.wins + 1)
            RoundResult.LOSS -> current.copy(losses = current.losses + 1)
            RoundResult.DRAW -> current.copy(draws = current.draws + 1)
        }
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
    ) {
        if (winner == null) {
            playerNames.values.forEach { store.addResult(it, RoundResult.DRAW) }
            return
        }
        playerNames[winner]?.let { store.addResult(it, RoundResult.WIN) }
        playerNames[winner.other]?.let { store.addResult(it, RoundResult.LOSS) }
    }

    fun top(limit: Int = 10): List<LeaderboardEntry> =
        store.loadAll()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, PlayerStats>> { it.value.wins }.thenBy { it.key })
            .take(limit)
            .map { LeaderboardEntry(it.key, it.value) }
}
