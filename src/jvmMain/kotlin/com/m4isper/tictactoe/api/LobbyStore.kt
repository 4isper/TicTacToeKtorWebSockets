package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.BotLevel
import com.m4isper.tictactoe.game.GameSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/** One named room's durable settings and round, enough to recreate it after a restart. */
@Serializable
data class PersistedRoom(
    val name: String,
    val boardSize: Int,
    val winLength: Int,
    val botLevel: BotLevel? = null,
    val secondBotLevel: BotLevel? = null,
    val turnSeconds: Int,
    val clockSeconds: Int,
    val seriesTarget: Int,
    val snapshot: GameSnapshot,
)

@Serializable
data class PersistedLobby(
    val rooms: List<PersistedRoom> = emptyList(),
    /** player key -> lowercased name: the persistent ownership claimed by the join keys. */
    val names: Map<String, String> = emptyMap(),
)

interface LobbyStore {
    fun load(): PersistedLobby?
    fun save(lobby: PersistedLobby)
}

/** JSON-file store with an atomic write (temp file + rename), mirroring JsonScoreStore. */
class JsonLobbyStore(private val file: File) : LobbyStore {
    private val logger = LoggerFactory.getLogger(JsonLobbyStore::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override fun load(): PersistedLobby? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<PersistedLobby>(file.readText()) }
            .getOrElse { error ->
                // keep the unreadable original instead of silently losing the rooms and names
                val backup = File(file.parentFile, "${file.name}.corrupt")
                runCatching { file.copyTo(backup, overwrite = true) }
                    .onFailure { logger.error("Could not back up the corrupt lobby {}", file, it) }
                logger.warn("Lobby {} is corrupt; starting empty, original kept at {}", file, backup, error)
                null
            }
    }

    override fun save(lobby: PersistedLobby) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(json.encodeToString(lobby))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
