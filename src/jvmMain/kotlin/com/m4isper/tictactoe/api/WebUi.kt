package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.stats.LeaderboardResponse
import com.m4isper.tictactoe.stats.Scoreboard
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.namePlaceholder
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.rematchButtonText
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
import kotlinx.serialization.json.Json
import java.io.File

private const val CLIENT_DIST = "build/dist/js/productionExecutable"

private suspend fun ApplicationCall.respondCss(builder: CssBuilder.() -> Unit) {
    respondText(CssBuilder().apply(builder).toString(), ContentType.Text.CSS)
}

private val leaderboardJson = Json { encodeDefaults = true }

fun Application.webUi(scoreboard: Scoreboard) {
    routing {
        staticFiles("/static", File(CLIENT_DIST))

        get("/leaderboard") {
            call.respondText(
                leaderboardJson.encodeToString(LeaderboardResponse(scoreboard.top(limit = 10))),
                ContentType.Application.Json,
            )
        }

        get("/player") {
            val profile = scoreboard.profile(call.request.queryParameters["name"])
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(leaderboardJson.encodeToString(profile), ContentType.Application.Json)
            }
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
                rule(".cell.winning") {
                    backgroundColor = Color("#2e7d4f")
                    animation += Animation("win-pulse", 1200.ms, Timing.easeInOut, iterationCount = IterationCount.infinite)
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
                    div { id = "board" }
                    div { id = "error" }
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
