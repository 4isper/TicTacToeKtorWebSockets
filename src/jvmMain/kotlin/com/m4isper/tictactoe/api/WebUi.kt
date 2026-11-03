package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.protocol.REACTION_EMOJIS
import com.m4isper.tictactoe.stats.LeaderboardResponse
import com.m4isper.tictactoe.stats.Scoreboard
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.namePlaceholder
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.roomsTitle
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.css.*
import kotlinx.css.properties.*
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import java.awt.Color as AwtColor

private const val CLIENT_DIST = "build/dist/js/productionExecutable"

private suspend fun ApplicationCall.respondCss(builder: CssBuilder.() -> Unit) {
    respondText(CssBuilder().apply(builder).toString(), ContentType.Text.CSS)
}

private val apiJson = Json { encodeDefaults = true }

@Serializable
private data class WebManifestIcon(val src: String, val sizes: String, val type: String = "image/svg+xml")

@Serializable
private data class WebManifest(
    val name: String,
    val short_name: String,
    val start_url: String = "/",
    val display: String = "standalone",
    val background_color: String = "#14141f",
    val theme_color: String = "#14141f",
    val icons: List<WebManifestIcon>,
)

private const val ICON_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">""" +
        """<rect width="100" height="100" rx="18" fill="#14141f"/>""" +
        """<g stroke="#7aa2f7" stroke-width="7" stroke-linecap="round"><line x1="22" y1="22" x2="46" y2="46"/><line x1="46" y1="22" x2="22" y2="46"/></g>""" +
        """<g stroke="#e8e8f0" stroke-width="7"><circle cx="66" cy="66" r="13" fill="none"/></g></svg>"""

private const val SERVICE_WORKER_JS =
    """self.addEventListener("install", function (e) { self.skipWaiting() });""" +
        """self.addEventListener("activate", function (e) { self.clients.claim() });""" +
        """self.addEventListener("fetch", function (e) { /* pass-through: the game is online-only */ });"""

private val iconCache = ConcurrentHashMap<Int, ByteArray>()

/** The manifest icon drawn with Java2D — the same X/O glyph the SVG one carries. */
private fun iconPng(size: Int): ByteArray = iconCache.getOrPut(size) {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val unit = size / 100.0
    g.color = AwtColor(0x14, 0x14, 0x1f)
    g.fillRect(0, 0, size, size)
    g.stroke = BasicStroke((7 * unit).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    g.color = AwtColor(0x7a, 0xa2, 0xf7)
    g.drawLine((22 * unit).toInt(), (22 * unit).toInt(), (46 * unit).toInt(), (46 * unit).toInt())
    g.drawLine((46 * unit).toInt(), (22 * unit).toInt(), (22 * unit).toInt(), (46 * unit).toInt())
    g.color = AwtColor(0xe8, 0xe8, 0xf0)
    g.drawOval((53 * unit).toInt(), (53 * unit).toInt(), (26 * unit).toInt(), (26 * unit).toInt())
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    out.toByteArray()
}

fun Application.webUi(
    scoreboard: Scoreboard,
    lobby: GameLobby,
) {
    routing {
        staticFiles("/static", File(CLIENT_DIST))

        get("/leaderboard") {
            call.respondText(
                apiJson.encodeToString(LeaderboardResponse(scoreboard.top(limit = 10))),
                ContentType.Application.Json,
            )
        }

        get("/player") {
            val profile = scoreboard.profile(call.request.queryParameters["name"])
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(apiJson.encodeToString(profile), ContentType.Application.Json)
            }
        }

        get("/rooms") {
            call.respondText(
                apiJson.encodeToString(RoomsResponse(lobby.roomSnapshots())),
                ContentType.Application.Json,
            )
        }

        get("/manifest.webmanifest") {
            val lang = parseLang(call.request.queryParameters["lang"])
            val manifest = WebManifest(
                name = when (lang) {
                    Lang.RU -> "Крестики-нолики"
                    Lang.EN -> "Tic-tac-toe"
                },
                short_name = when (lang) {
                    Lang.RU -> "Крестики"
                    Lang.EN -> "Tic-tac-toe"
                },
                icons = listOf(
                    WebManifestIcon("/icon.svg", "any"),
                    WebManifestIcon("/icon-192.png", "192x192", "image/png"),
                    WebManifestIcon("/icon-512.png", "512x512", "image/png"),
                ),
            )
            call.respondText(apiJson.encodeToString(manifest), ContentType.Application.Json)
        }

        get("/icon.svg") {
            call.respondText(ICON_SVG, ContentType.Image.SVG)
        }

        get("/icon-192.png") {
            call.respondBytes(iconPng(192), ContentType.Image.PNG)
        }

        get("/icon-512.png") {
            call.respondBytes(iconPng(512), ContentType.Image.PNG)
        }

        get("/sw.js") {
            call.respondText(SERVICE_WORKER_JS, ContentType.Text.JavaScript)
        }

        get("/styles.css") {
            call.respondCss {
                rule("body") {
                    fontFamily = "system-ui, sans-serif"
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    alignItems = Align.center
                    backgroundColor = Color("#14141f")
                    color = Color("#e8e8f0")
                }
                rule("#status") {
                    fontSize = 22.px
                    minHeight = 30.px
                }
                rule("#error") {
                    color = Color("#ff7b7b")
                    minHeight = 24.px
                }
                rule("#score") {
                    fontSize = 18.px
                    minHeight = 24.px
                    color = Color("#9a9ab8")
                }
                rule("#copy-link") {
                    margin = Margin(vertical = 8.px)
                    padding = Padding(horizontal = 14.px, vertical = 8.px)
                    fontSize = 15.px
                    backgroundColor = Color("#1f1f33")
                    color = Color("#e8e8f0")
                    borderWidth = 0.px
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                }
                rule("#rematch, #draw") {
                    display = Display.none
                    margin = Margin(vertical = 4.px)
                    padding = Padding(horizontal = 14.px, vertical = 8.px)
                    fontSize = 15.px
                    backgroundColor = Color("#1f1f33")
                    color = Color("#e8e8f0")
                    borderWidth = 0.px
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                }
                rule("#rematch:disabled, #draw:disabled") {
                    opacity = 0.6
                    cursor = Cursor.default
                }
                rule(".name-row") {
                    display = Display.flex
                    gap = 4.px
                    margin = Margin(vertical = 4.px)
                }
                rule("#name") {
                    backgroundColor = Color("#1f1f33")
                    color = Color("#e8e8f0")
                    borderWidth = 0.px
                    borderRadius = 8.px
                    padding = Padding(horizontal = 10.px, vertical = 8.px)
                    fontSize = 15.px
                }
                rule("#name::placeholder") {
                    color = Color("#6a6a85")
                }
                rule("#set-name") {
                    padding = Padding(horizontal = 14.px, vertical = 8.px)
                    fontSize = 15.px
                    backgroundColor = Color("#1f1f33")
                    color = Color("#e8e8f0")
                    borderWidth = 0.px
                    borderRadius = 8.px
                    cursor = Cursor.pointer
                }
                rule("#turn-bar") {
                    display = Display.none
                    height = 6.px
                    backgroundColor = Color("#1f1f33")
                    borderRadius = 3.px
                    margin = Margin(vertical = 6.px)
                    overflow = Overflow.hidden
                }
                rule("#turn-bar-fill") {
                    width = 100.pct
                    height = 100.pct
                    backgroundColor = Color("#7aa2f7")
                    transition += Transition("width", 250.ms, Timing.linear)
                }
                rule(".row") {
                    display = Display.flex
                    gap = 4.px
                }
                rule(".cell") {
                    backgroundColor = Color("#1f1f33")
                    color = Color.white
                    borderRadius = 8.px
                    borderWidth = 0.px
                    cursor = Cursor.pointer
                }
                rule(".cell:disabled") {
                    cursor = Cursor.default
                    opacity = 0.85
                }
                rule(".cell.pop") {
                    animation += Animation("cell-pop", 180.ms, Timing.easeOut)
                }
                rule(".cell.last-move") {
                    boxSizing = BoxSizing.borderBox
                    borderWidth = 2.px
                    borderStyle = BorderStyle.solid
                    borderColor = Color("#7aa2f7")
                }
                rule(".cell.winning") {
                    backgroundColor = Color("#2e7d4f")
                    animation += Animation("win-pulse", 1200.ms, Timing.easeInOut, iterationCount = IterationCount.infinite)
                }
                rule(".replay-controls") {
                    display = Display.flex
                    alignItems = Align.center
                    gap = 4.px
                    margin = Margin(vertical = 4.px)
                }
                rule(".mini-cell") {
                    width = 26.px
                    height = 26.px
                    backgroundColor = Color("#1f1f33")
                    color = Color.white
                    fontSize = 13.px
                    lineHeight = LineHeight("26px")
                    textAlign = TextAlign.center
                }
                rule("#board") {
                    position = Position.relative
                }
                rule("#reactions") {
                    display = Display.flex
                    gap = 8.px
                    margin = Margin(vertical = 6.px)
                }
                rule(".reaction") {
                    backgroundColor = Color("#1f1f33")
                    borderWidth = 0.px
                    borderRadius = 8.px
                    padding = Padding(horizontal = 10.px, vertical = 4.px)
                    fontSize = 18.px
                    cursor = Cursor.pointer
                }
                rule(".reaction-bubble") {
                    position = Position.absolute
                    bottom = 20.pct
                    fontSize = 30.px
                    pointerEvents = PointerEvents.none
                    animation += Animation("reaction-float", 2000.ms, Timing.easeOut, fillMode = FillMode.forwards)
                }
                rule("@keyframes reaction-float") {
                    rule("from") {
                        opacity = 1
                        transform { translateY(0.px) }
                    }
                    rule("to") {
                        opacity = 0
                        transform { translateY((-60).px) }
                    }
                }
                rule("@keyframes cell-pop") {
                    rule("from") {
                        opacity = 0
                        transform { scale(0.4) }
                    }
                    rule("to") {
                        opacity = 1
                        transform { scale(1) }
                    }
                }
                rule("@keyframes win-pulse") {
                    rule("from") {
                        opacity = 1
                    }
                    rule("to") {
                        opacity = 0.65
                    }
                }
            }
        }

        get("/") {
            val room = call.request.queryParameters["room"]
            val pageLang = parseLang(call.request.queryParameters["lang"])
            call.respondHtml {
                lang = when (pageLang) {
                    Lang.RU -> "ru"
                    Lang.EN -> "en"
                }
                head {
                    meta { charset = "utf-8" }
                    meta(name = "viewport", content = "width=device-width, initial-scale=1")
                    meta(name = "theme-color", content = "#14141f")
                    link(rel = "manifest", href = "/manifest.webmanifest")
                    link(rel = "icon", href = "/icon.svg")
                    title {
                        +"${when (pageLang) {
                            Lang.RU -> "Крестики-нолики"
                            Lang.EN -> "Tic-tac-toe"
                        }}"
                    }
                    link(rel = "stylesheet", href = "/styles.css")
                }
                body {
                    h1 {
                        +"${when (pageLang) {
                            Lang.RU -> "Крестики-нолики"
                            Lang.EN -> "Tic-tac-toe"
                        }}"
                    }
                    room?.let {
                        p {
                            +"${when (pageLang) {
                                Lang.RU -> "Комната: $it"
                                Lang.EN -> "Room: $it"
                            }}"
                        }
                        button(classes = "secondary") {
                            id = "copy-link"
                            +copyLinkText(pageLang)
                        }
                    }
                    div { id = "status" }
                    div { id = "score" }
                    div(classes = "name-row") {
                        input(InputType.text) {
                            id = "name"
                            placeholder = namePlaceholder(pageLang)
                            maxLength = "20"
                        }
                        button(classes = "secondary") {
                            id = "set-name"
                            +"ОК"
                        }
                        button(classes = "secondary") {
                            id = "sound"
                            title = soundTitle(muted = false, lang = pageLang)
                            +soundIcon(muted = false)
                        }
                    }
                    button(classes = "secondary") {
                        id = "rematch"
                        +rematchButtonText(waiting = false, lang = pageLang)
                    }
                    button(classes = "secondary") {
                        id = "draw"
                        +drawButtonText(iOffered = false, opponentOffered = false, lang = pageLang)
                    }
                    div {
                        id = "turn-bar"
                        div { id = "turn-bar-fill" }
                    }
                    div { id = "board" }
                    div { id = "error" }
                    div {
                        id = "reactions"
                        REACTION_EMOJIS.forEach { emoji ->
                            button(classes = "reaction") {
                                attributes["aria-label"] = emoji
                                +emoji
                            }
                        }
                    }
                    div {
                        id = "rooms"
                        h2 { +roomsTitle(pageLang) }
                        ul { id = "rooms-rows" }
                    }
                    div {
                        id = "leaderboard"
                        h2 { +leaderboardTitle(pageLang) }
                        ul { id = "leaderboard-rows" }
                    }
                    div { id = "profile" }
                    script(src = "/static/app.js") {}
                }
            }
        }
    }
}
