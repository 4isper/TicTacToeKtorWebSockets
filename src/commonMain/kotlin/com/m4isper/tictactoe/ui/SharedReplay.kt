package com.m4isper.tictactoe.ui

import com.m4isper.tictactoe.domain.Cell
import com.m4isper.tictactoe.domain.GameRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A replay packed into a URL fragment: no server state, the link itself carries the whole game. */
data class SharedReplay(
    val boardSize: Int,
    val winLength: Int,
    val moves: List<Cell>,
    val xName: String? = null,
    val oName: String? = null,
)

@Serializable
private data class PackedReplay(
    val s: Int,
    val w: Int,
    val m: List<List<Int>>,
    val n: String? = null,
    val o: String? = null,
)

private val replayJson = Json { encodeDefaults = false }

/** A link is untrusted input: bound it before decoding, and bound every dimension after. */
private const val MAX_FRAGMENT_LENGTH = 4_096
private const val MAX_NAME_LENGTH = 20

@OptIn(ExperimentalEncodingApi::class)
private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

fun replayToFragment(replay: SharedReplay): String {
    val packed = PackedReplay(
        s = replay.boardSize,
        w = replay.winLength,
        m = replay.moves.map { listOf(it.x, it.y) },
        n = replay.xName,
        o = replay.oName,
    )
    return "r=" + base64.encode(replayJson.encodeToString(packed).encodeToByteArray())
}

/** Tolerant on purpose: garbage, a broken paste or a hostile link simply yields null. */
@OptIn(ExperimentalEncodingApi::class)
fun replayFromFragment(fragment: String?): SharedReplay? = runCatching {
    val payload = fragment
        ?.takeIf { it.length <= MAX_FRAGMENT_LENGTH && it.startsWith("r=") }
        ?.removePrefix("r=")
        ?.trimEnd('=') ?: return null
    val packed = replayJson.decodeFromString<PackedReplay>(base64.decode(payload).decodeToString())
    if (packed.s !in GameRules.MIN_BOARD_SIZE..GameRules.MAX_BOARD_SIZE) return null
    if (packed.w !in GameRules.MIN_WIN_LENGTH..packed.s) return null
    if (packed.m.size > packed.s * packed.s) return null
    if (packed.n != null && packed.n.length > MAX_NAME_LENGTH) return null
    if (packed.o != null && packed.o.length > MAX_NAME_LENGTH) return null
    if (packed.m.any { it.size != 2 || it[0] !in 0 until packed.s || it[1] !in 0 until packed.s }) return null
    SharedReplay(
        boardSize = packed.s,
        winLength = packed.w,
        moves = packed.m.map { Cell(it[0], it[1]) },
        xName = packed.n,
        oName = packed.o,
    )
}.getOrNull()
