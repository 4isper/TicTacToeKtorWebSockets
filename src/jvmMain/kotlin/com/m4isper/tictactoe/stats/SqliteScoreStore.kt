package com.m4isper.tictactoe.stats

import com.m4isper.tictactoe.domain.Cell
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.DriverManager
import java.sql.Types

/**
 * SQLite store: one file, one connection, one writer at a time — plenty for this scale.
 * Point `SCOREBOARD_FILE` at a `.db`/`.sqlite` path to use it instead of the JSON store.
 */
class SqliteScoreStore(private val file: File) : ScoreStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    init {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS players (" +
                    "name TEXT PRIMARY KEY," +
                    "wins INTEGER NOT NULL DEFAULT 0," +
                    "losses INTEGER NOT NULL DEFAULT 0," +
                    "draws INTEGER NOT NULL DEFAULT 0," +
                    "rating INTEGER NOT NULL DEFAULT 1000)",
            )
            statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS history (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "player TEXT NOT NULL," +
                    "epoch_ms INTEGER NOT NULL," +
                    "opponent TEXT," +
                    "result TEXT NOT NULL," +
                    "moves TEXT NOT NULL," +
                    "board_size INTEGER NOT NULL," +
                    "rating_after INTEGER)",
            )
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
        connection.autoCommit = false
        try {
            connection
                .prepareStatement(
                    "INSERT INTO players(name, wins, losses, draws, rating) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT(name) DO UPDATE SET wins = ?, losses = ?, draws = ?, rating = ?",
                )
                .use { statement ->
                    statement.setString(1, name)
                    statement.setInt(2, next.wins)
                    statement.setInt(3, next.losses)
                    statement.setInt(4, next.draws)
                    statement.setInt(5, next.rating)
                    statement.setInt(6, next.wins)
                    statement.setInt(7, next.losses)
                    statement.setInt(8, next.draws)
                    statement.setInt(9, next.rating)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "INSERT INTO history(player, epoch_ms, opponent, result, moves, board_size, rating_after) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                )
                .use { statement ->
                    statement.setString(1, name)
                    statement.setLong(2, atMs)
                    if (opponent == null) statement.setNull(3, Types.VARCHAR) else statement.setString(3, opponent)
                    statement.setString(4, result.name)
                    statement.setString(5, json.encodeToString(moves))
                    statement.setInt(6, boardSize)
                    if (ratingAfter == null) statement.setNull(7, Types.INTEGER) else statement.setInt(7, ratingAfter)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "DELETE FROM history WHERE player = ? AND seq NOT IN " +
                        "(SELECT seq FROM history WHERE player = ? ORDER BY seq DESC LIMIT ?)",
                )
                .use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, name)
                    statement.setInt(3, HISTORY_LIMIT)
                    statement.executeUpdate()
                }
            connection.commit()
        } finally {
            connection.autoCommit = true
        }
    }

    override fun loadAll(): Map<String, PlayerStats> = synchronized(connection) { loadAllLocked() }

    private fun loadAllLocked(): Map<String, PlayerStats> {
        val stats = mutableMapOf<String, PlayerStats>()
        connection
            .createStatement()
            .use { statement ->
                statement
                    .executeQuery(
                        "SELECT p.name, p.wins, p.losses, p.draws, p.rating, " +
                            "h.epoch_ms, h.opponent, h.result, h.moves, h.board_size, h.rating_after " +
                            "FROM players p LEFT JOIN history h ON h.player = p.name " +
                            "ORDER BY p.name, h.seq DESC",
                    )
                    .use { rows ->
                        while (rows.next()) {
                            val name = rows.getString("name")
                            val base = stats[name] ?: PlayerStats(
                                wins = rows.getInt("wins"),
                                losses = rows.getInt("losses"),
                                draws = rows.getInt("draws"),
                                rating = rows.getInt("rating"),
                            )
                            val record = rows.getLong("epoch_ms").takeIf { !rows.wasNull() }?.let {
                                MatchRecord(
                                    epochMs = it,
                                    opponent = rows.getString("opponent"),
                                    result = RoundResult.valueOf(rows.getString("result")),
                                    moves = runCatching { json.decodeFromString<List<Cell>>(rows.getString("moves")) }
                                        .getOrElse { emptyList() },
                                    boardSize = rows.getInt("board_size"),
                                    ratingAfter = rows.getInt("rating_after").takeIf { !rows.wasNull() },
                                )
                            }
                            stats[name] = if (record == null) base else base.copy(history = base.history + record)
                        }
                    }
            }
        return stats
    }

    private companion object {
        const val HISTORY_LIMIT = 20
    }
}
