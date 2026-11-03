package com.m4isper.tictactoe.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.css.*
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import java.io.File

private const val CLIENT_DIST = "build/dist/js/productionExecutable"

private suspend fun ApplicationCall.respondCss(builder: CssBuilder.() -> Unit) {
    respondText(CssBuilder().apply(builder).toString(), ContentType.Text.CSS)
}

fun Application.webUi() {
    routing {
        staticFiles("/static", File(CLIENT_DIST))

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
            }
        }

        get("/") {
            val room = call.request.queryParameters["room"]
            call.respondHtml {
                lang = "ru"
                head {
                    meta { charset = "utf-8" }
                    meta(name = "viewport", content = "width=device-width, initial-scale=1")
                    title { +"Крестики-нолики" }
                    link(rel = "stylesheet", href = "/styles.css")
                }
                body {
                    h1 { +"Крестики-нолики" }
                    room?.let {
                        p { +"Комната: $it" }
                        button(classes = "secondary") {
                            id = "copy-link"
                            +"Скопировать ссылку"
                        }
                    }
                    div { id = "status" }
                    div { id = "score" }
                    div { id = "board" }
                    div { id = "error" }
                    script(src = "/static/app.js") {}
                }
            }
        }
    }
}
