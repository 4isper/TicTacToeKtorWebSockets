package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager
import java.sql.Statement

/**
 * SQLite store: one row per player holding the whole [PlayerStats], so every field
 * the shared result math computes survives a restart. One file, one connection,
 * one writer at a time. Point `SCOREBOARD_FILE` at a `.db` to use it.
 */
class SqliteScoreStore(file: File) : ScoreStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_stats (" +
                    "name TEXT PRIMARY KEY," +
                    "data TEXT NOT NULL)",
            )
            migrateLegacy(statement)
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
    ): Unit = synchronized(connection) {
        val next = statsAfter(loadAllLocked()[name], result, opponent, atMs, moves, boardSize, rating, ratingAfter)
        connection
            .prepareStatement(
                "INSERT INTO player_stats(name, data) VALUES (?, ?) " +
                    "ON CONFLICT(name) DO UPDATE SET data = excluded.data",
            )
            .use { statement ->
                statement.setString(1, name)
                statement.setString(2, json.encodeToString(next))
                statement.executeUpdate()
            }
    }

    override fun loadAll(): Map<String, PlayerStats> = synchronized(connection) { loadAllLocked() }

    private fun loadAllLocked(): Map<String, PlayerStats> {
        val stats = mutableMapOf<String, PlayerStats>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name, data FROM player_stats").use { rows ->
                while (rows.next()) {
                    stats[rows.getString("name")] = json.decodeFromString(rows.getString("data"))
                }
            }
        }
        return stats
    }

    /** Older files kept normalized players/history tables; fold them into blobs once and leave them intact. */
    private fun migrateLegacy(statement: Statement) {
        if (!tableExists(statement, "players") || loadAllLocked().isNotEmpty()) return
        val legacy = legacyStats(statement)
        connection
            .prepareStatement("INSERT OR REPLACE INTO player_stats(name, data) VALUES (?, ?)")
            .use { upsert ->
                legacy.forEach { (name, stats) ->
                    upsert.setString(1, name)
                    upsert.setString(2, json.encodeToString(stats))
                    upsert.executeUpdate()
                }
            }
    }

    private fun tableExists(statement: Statement, name: String): Boolean = statement
        .executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name'")
        .use { it.next() }

    private fun legacyStats(statement: Statement): Map<String, PlayerStats> {
        val stats = mutableMapOf<String, PlayerStats>()
        statement.executeQuery("SELECT name, wins, losses, draws, rating FROM players").use { rows ->
            while (rows.next()) {
                stats[rows.getString("name")] = PlayerStats(
                    wins = rows.getInt("wins"),
                    losses = rows.getInt("losses"),
                    draws = rows.getInt("draws"),
                    rating = rows.getInt("rating"),
                )
            }
        }
        if (tableExists(statement, "history")) {
            statement
                .executeQuery(
                    "SELECT player, epoch_ms, opponent, result, moves, board_size, rating_after " +
                        "FROM history ORDER BY seq DESC",
                )
                .use { rows ->
                    while (rows.next()) {
                        val name = rows.getString("player")
                        val current = stats[name] ?: continue
                        val record = MatchRecord(
                            epochMs = rows.getLong("epoch_ms"),
                            opponent = rows.getString("opponent"),
                            result = RoundResult.valueOf(rows.getString("result")),
                            moves = runCatching { json.decodeFromString<List<Cell>>(rows.getString("moves")) }
                                .getOrElse { emptyList() },
                            boardSize = rows.getInt("board_size"),
                            ratingAfter = rows.getInt("rating_after").takeIf { !rows.wasNull() },
                        )
                        stats[name] = current.copy(history = current.history + record)
                    }
                }
        }
        return stats.mapValues { (_, value) ->
            value.copy(
                ratedGames = value.history.count { it.ratingAfter != null },
                opponents = value.history.fold(emptyMap<String, HeadToHead>()) { tally, record ->
                    tally.bump(record.opponent, record.result)
                },
            )
        }
    }
}
