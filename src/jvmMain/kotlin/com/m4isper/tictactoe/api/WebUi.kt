package com.m4isper.tictactoe.api

import com.m4isper.tictactoe.domain.GameRules
import com.m4isper.tictactoe.domain.MAX_CHAT_LENGTH
import com.m4isper.tictactoe.protocol.REACTION_EMOJIS
import com.m4isper.tictactoe.stats.LeaderboardResponse
import com.m4isper.tictactoe.stats.Scoreboard
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.ThemeMode
import com.m4isper.tictactoe.ui.botExhibitionText
import com.m4isper.tictactoe.ui.botLevelText
import com.m4isper.tictactoe.ui.botNoneText
import com.m4isper.tictactoe.ui.chatPlaceholder
import com.m4isper.tictactoe.ui.chatSendText
import com.m4isper.tictactoe.ui.chatTitle
import com.m4isper.tictactoe.ui.clockOffText
import com.m4isper.tictactoe.ui.copyLinkText
import com.m4isper.tictactoe.ui.createGameSubmit
import com.m4isper.tictactoe.ui.createGameTitle
import com.m4isper.tictactoe.ui.detectLang
import com.m4isper.tictactoe.ui.drawButtonText
import com.m4isper.tictactoe.ui.fieldBotText
import com.m4isper.tictactoe.ui.fieldClockText
import com.m4isper.tictactoe.ui.fieldRoomText
import com.m4isper.tictactoe.ui.fieldSeriesText
import com.m4isper.tictactoe.ui.fieldSizeText
import com.m4isper.tictactoe.ui.fieldTurnText
import com.m4isper.tictactoe.ui.fieldWinText
import com.m4isper.tictactoe.ui.hintText
import com.m4isper.tictactoe.ui.leaderboardLegend
import com.m4isper.tictactoe.ui.leaderboardTitle
import com.m4isper.tictactoe.ui.moveListLabel
import com.m4isper.tictactoe.ui.namePlaceholder
import com.m4isper.tictactoe.ui.okText
import com.m4isper.tictactoe.ui.pageDescription
import com.m4isper.tictactoe.ui.parseLang
import com.m4isper.tictactoe.ui.profileNotFoundText
import com.m4isper.tictactoe.ui.quickPlayText
import com.m4isper.tictactoe.ui.reactionLabel
import com.m4isper.tictactoe.ui.rematchButtonText
import com.m4isper.tictactoe.ui.resignButtonText
import com.m4isper.tictactoe.ui.roomsTitle
import com.m4isper.tictactoe.ui.searchPlaceholder
import com.m4isper.tictactoe.ui.seriesOffText
import com.m4isper.tictactoe.ui.seriesTargetText
import com.m4isper.tictactoe.ui.skipLinkText
import com.m4isper.tictactoe.ui.soundIcon
import com.m4isper.tictactoe.ui.soundTitle
import com.m4isper.tictactoe.ui.themeColor
import com.m4isper.tictactoe.ui.themeIcon
import com.m4isper.tictactoe.ui.themeTitle
import com.m4isper.tictactoe.ui.undoButtonText
import com.m4isper.tictactoe.ui.urlEncode
import com.m4isper.tictactoe.ui.winPlaceholderText
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.css.*
import kotlinx.html.FORM
import kotlinx.html.FormMethod
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.HtmlBlockTag
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.numberInput
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textInput
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
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
    @SerialName("short_name") val shortName: String,
    @SerialName("start_url") val startUrl: String = "/",
    val display: String = "standalone",
    @SerialName("background_color") val backgroundColor: String = "#14141f",
    @SerialName("theme_color") val themeColor: String = "#14141f",
    val icons: List<WebManifestIcon>,
)

private const val ICON_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">""" +
        """<rect width="100" height="100" rx="18" fill="#14141f"/>""" +
        """<g stroke="#7aa2f7" stroke-width="7" stroke-linecap="round"><line x1="22" y1="22" x2="46" y2="46"/><line x1="46" y1="22" x2="22" y2="46"/></g>""" +
        """<g stroke="#e8e8f0" stroke-width="7"><circle cx="66" cy="66" r="13" fill="none"/></g></svg>"""

private const val SERVICE_WORKER_JS =
    """const CACHE = "ttt-v3"; const OFFLINE_URL = "/offline";""" +
        """const SHELL = ["/", "/styles.css", "/static/app.js", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png", OFFLINE_URL];""" +
        """const LIVE = ["/rooms", "/leaderboard", "/player"];""" +
        """self.addEventListener("install", function (e) {""" +
        """e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); })); });""" +
        """self.addEventListener("activate", function (e) {""" +
        """e.waitUntil(caches.keys().then(function (keys) {""" +
        """return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));""" +
        """}).then(function () { return self.clients.claim(); })); });""" +
        """self.addEventListener("fetch", function (e) {""" +
        """if (e.request.mode === "navigate") {""" +
        """e.respondWith(fetch(e.request).catch(function () {""" +
        """return caches.match(e.request).then(function (page) { return page || caches.match(OFFLINE_URL); });""" +
        """})); return; }""" +
        """if (e.request.method !== "GET") return;""" +
        """var url = new URL(e.request.url);""" +
        """if (url.origin !== self.location.origin) return;""" +
        """if (LIVE.indexOf(url.pathname) !== -1) {""" +
        """e.respondWith(fetch(e.request).then(function (res) {""" +
        """if (res && res.ok) { var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put(e.request, copy); }); }""" +
        """return res; }).catch(function () {""" +
        """return caches.match(e.request).then(function (hit) { return hit || Response.error(); }); })); return; }""" +
        """e.respondWith(caches.match(e.request).then(function (hit) {""" +
        """return hit || fetch(e.request).then(function (res) {""" +
        """if (res && res.ok) {""" +
        """var copy = res.clone(); caches.open(CACHE).then(function (c) { c.put(e.request, copy); }); }""" +
        """return res; }); })); });"""

private const val OFFLINE_HTML =
    """<!doctype html><html lang="ru"><head><meta charset="utf-8">""" +
        """<meta name="viewport" content="width=device-width, initial-scale=1"><title>Offline</title></head>""" +
        """<body style="font-family:system-ui,sans-serif;background:#14141f;color:#e8e8f0;""" +
        """display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center">""" +
        """<div><h1>Нет соединения</h1><p>Партия живёт на сервере — проверьте сеть и обновите страницу.</p>""" +
        """<p style="opacity:.7">You are offline — reconnect and reload.</p></div></body></html>"""

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
        apiRoutes(scoreboard, lobby)
        assetRoutes()
        pageRoute()
    }
}

private fun Route.apiRoutes(
    scoreboard: Scoreboard,
    lobby: GameLobby,
) {
    get("/leaderboard") {
        call.respondText(
            apiJson.encodeToString(LeaderboardResponse(scoreboard.top(limit = 10))),
            ContentType.Application.Json,
        )
    }

    get("/player") {
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val profile = scoreboard.profile(call.request.queryParameters["name"], page)
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
}

private fun Route.assetRoutes() {
    get("/manifest.webmanifest") {
        val lang = parseLang(call.request.queryParameters["lang"])
        val manifest = WebManifest(
            name = when (lang) {
                Lang.RU -> "Крестики-нолики"
                Lang.EN -> "Tic-tac-toe"
            },
            shortName = when (lang) {
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

    get("/offline") {
        call.respondText(OFFLINE_HTML, ContentType.Text.Html)
    }

    get("/styles.css") {
        call.respondCss { styles() }
    }
}

private fun Route.pageRoute() {
    get("/") {
        val room = call.request.queryParameters["room"]
        // an explicit lang parameter wins; otherwise the browser's own preference decides
        val langParam = call.request.queryParameters["lang"]?.takeIf { it.isNotBlank() }
        val pageLang = langParam?.let(::parseLang)
            ?: detectLang(call.request.headers[HttpHeaders.AcceptLanguage])
        call.respondHtml { page(pageLang, room, langParam) }
    }
}

private fun HTML.page(pageLang: Lang, room: String?, langParam: String?) {
    lang = when (pageLang) {
        Lang.RU -> "ru"
        Lang.EN -> "en"
    }
    head { pageHead(pageLang) }
    body {
        a(href = "#main", classes = "skip-link") {
            id = "skip-link"
            +skipLinkText(pageLang)
        }
        main {
            id = "main"
            attributes["tabindex"] = "-1"
            pageHeading(pageLang, room, langParam)
            controls(pageLang)
            boardArea(pageLang)
            panels(pageLang, langParam)
        }
        script(src = "/static/app.js") {}
    }
}

private fun HEAD.pageHead(pageLang: Lang) {
    meta { charset = "utf-8" }
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    meta(name = "description", content = pageDescription(pageLang))
    meta(name = "theme-color", content = themeColor(ThemeMode.AUTO, prefersLight = true)) {
        attributes["media"] = "(prefers-color-scheme: light)"
        attributes["id"] = "theme-color-light"
    }
    meta(name = "theme-color", content = themeColor(ThemeMode.AUTO, prefersLight = false)) {
        attributes["id"] = "theme-color-dark"
    }
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

private fun HtmlBlockTag.pageHeading(pageLang: Lang, room: String?, langParam: String?) {
    h1 {
        +"${when (pageLang) {
            Lang.RU -> "Крестики-нолики"
            Lang.EN -> "Tic-tac-toe"
        }}"
    }
    // a fresh named room: the player can invite a friend from the very first screen
    val quickRoom = "r" + UUID.randomUUID().toString().take(8)
    val quickParams = "room=$quickRoom" + langParam?.let { "&lang=${urlEncode(it)}" }.orEmpty()
    a(href = "/?$quickParams") {
        id = "quick-play"
        +quickPlayText(pageLang)
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
}

private fun HtmlBlockTag.controls(pageLang: Lang) {
    div {
        id = "identity"
        attributes["aria-live"] = "polite"
    }
    div(classes = "status-line") {
        div {
            id = "status"
            attributes.putAll(mapOf("role" to "status", "aria-live" to "polite"))
        }
        span {
            id = "timer"
            attributes["aria-live"] = "off"
        }
    }
    div {
        id = "score"
        attributes.putAll(mapOf("role" to "status", "aria-live" to "polite"))
    }
    div { id = "rules" }
    div {
        id = "clocks"
        attributes.putAll(mapOf("role" to "timer", "aria-live" to "off"))
    }
    div(classes = "name-row") {
        input(InputType.text) {
            id = "name"
            placeholder = namePlaceholder(pageLang)
            attributes["aria-label"] = namePlaceholder(pageLang)
            maxLength = "20"
        }
        button(classes = "secondary") {
            id = "set-name"
            +okText(pageLang)
        }
        button(classes = "secondary") {
            id = "sound"
            title = soundTitle(muted = false, lang = pageLang)
            attributes["aria-label"] = soundTitle(muted = false, lang = pageLang)
            +soundIcon(muted = false)
        }
        button(classes = "secondary") {
            id = "theme"
            title = themeTitle(ThemeMode.AUTO, lang = pageLang)
            attributes["aria-label"] = themeTitle(ThemeMode.AUTO, lang = pageLang)
            +themeIcon(ThemeMode.AUTO)
        }
    }
    div { id = "connection-actions" }
    actionRow(pageLang)
}

private fun HtmlBlockTag.actionRow(pageLang: Lang) {
    div(classes = "action-row") {
        button(classes = "secondary") {
            id = "rematch"
            +rematchButtonText(waiting = false, lang = pageLang)
        }
        button(classes = "secondary") {
            id = "draw"
            +drawButtonText(iOffered = false, opponentOffered = false, lang = pageLang)
        }
        button(classes = "secondary") {
            id = "undo"
            +undoButtonText(iOffered = false, opponentOffered = false, lang = pageLang)
        }
        button(classes = "secondary danger") {
            id = "resign"
            +resignButtonText(pageLang)
        }
        button(classes = "secondary") {
            id = "hint"
            +hintText(pageLang)
        }
    }
}

private fun HtmlBlockTag.boardArea(pageLang: Lang) {
    div {
        id = "turn-bar"
        div { id = "turn-bar-fill" }
    }
    div { id = "board" }
    ol {
        id = "moves"
        attributes["aria-label"] = moveListLabel(pageLang)
    }
    div {
        id = "error"
        attributes["role"] = "alert"
        attributes["aria-live"] = "assertive"
    }
    div {
        id = "shared-replay"
        attributes["style"] = "display:none"
    }
}

private fun HtmlBlockTag.panels(pageLang: Lang, langParam: String?) {
    createGameForm(pageLang, langParam)
    div {
        id = "reactions"
        REACTION_EMOJIS.forEach { emoji ->
            button(classes = "reaction") {
                attributes["aria-label"] = reactionLabel(emoji, pageLang)
                +emoji
            }
        }
    }
    chatPanel(pageLang)
    div {
        id = "rooms"
        attributes["role"] = "region"
        attributes["aria-label"] = roomsTitle(pageLang)
        h2 { +roomsTitle(pageLang) }
        ul { id = "rooms-rows" }
    }
    div {
        id = "leaderboard"
        attributes["role"] = "region"
        attributes["aria-label"] = leaderboardTitle(pageLang)
        h2 { +leaderboardTitle(pageLang) }
        p(classes = "legend") { +leaderboardLegend(pageLang) }
        input(InputType.text) {
            id = "player-search"
            placeholder = searchPlaceholder(pageLang)
            attributes["aria-label"] = searchPlaceholder(pageLang)
            maxLength = "20"
        }
        ul { id = "leaderboard-rows" }
    }
    div {
        id = "profile"
        attributes["role"] = "region"
        attributes["aria-label"] = searchPlaceholder(pageLang)
    }
}

/** The chat region: a live log and a single-line composer. */
private fun HtmlBlockTag.chatPanel(pageLang: Lang) {
    div {
        id = "chat"
        attributes["role"] = "region"
        attributes["aria-label"] = chatTitle(pageLang)
        h2 { +chatTitle(pageLang) }
        div {
            id = "chat-log"
            attributes["role"] = "log"
            attributes["aria-live"] = "polite"
            attributes["style"] = "height:140px;overflow-y:auto;background:var(--panel);border-radius:8px;padding:8px"
        }
        div {
            attributes["style"] = "display:flex;gap:8px;margin-top:6px"
            input(InputType.text) {
                id = "chat-input"
                placeholder = chatPlaceholder(pageLang)
                attributes["aria-label"] = chatPlaceholder(pageLang)
                maxLength = MAX_CHAT_LENGTH.toString()
            }
            button(classes = "secondary") {
                id = "chat-send"
                +chatSendText(pageLang)
            }
        }
    }
}

/** A GET form that composes a join link: the browser navigates, the client drops blank fields. */
private fun HtmlBlockTag.createGameForm(pageLang: Lang, langParam: String?) {
    div {
        id = "create"
        attributes["role"] = "region"
        attributes["aria-label"] = createGameTitle(pageLang)
        h2 { +createGameTitle(pageLang) }
        form(action = "/", method = FormMethod.get) {
            langParam?.let { hiddenInput(name = "lang") { value = it } }
            createBoardFields(pageLang)
            createTimeFields(pageLang)
            button {
                +createGameSubmit(pageLang)
            }
        }
    }
}

/** Room, board and win line — the same names the room link and /play accept. */
private fun FORM.createBoardFields(pageLang: Lang) {
    label {
        +fieldRoomText(pageLang)
        textInput(name = "room") {
            maxLength = "32"
            pattern = "[A-Za-z0-9_-]{1,32}"
        }
    }
    label {
        +fieldSizeText(pageLang)
        numberInput(name = "size") {
            min = GameRules.MIN_BOARD_SIZE.toString()
            max = GameRules.MAX_BOARD_SIZE.toString()
            value = GameRules.DEFAULT_BOARD_SIZE.toString()
        }
    }
    label {
        +fieldWinText(pageLang)
        numberInput(name = "win") {
            min = GameRules.MIN_WIN_LENGTH.toString()
            max = GameRules.MAX_BOARD_SIZE.toString()
            placeholder = winPlaceholderText(pageLang)
        }
    }
}

/** Turn and game-clock budgets, the practice bot level and the series target. */
private fun FORM.createTimeFields(pageLang: Lang) {
    label {
        +fieldTurnText(pageLang)
        numberInput(name = "turn") {
            min = GameRules.MIN_TURN_SECONDS.toString()
            max = GameRules.MAX_TURN_SECONDS.toString()
            value = GameRules.DEFAULT_TURN_SECONDS.toString()
        }
    }
    label {
        +fieldClockText(pageLang)
        numberInput(name = "clock") {
            min = GameRules.MIN_CLOCK_SECONDS.toString()
            max = GameRules.MAX_CLOCK_SECONDS.toString()
            placeholder = clockOffText(pageLang)
        }
    }
    label {
        +fieldBotText(pageLang)
        select {
            name = "bot"
            option {
                value = ""
                +botNoneText(pageLang)
            }
            option {
                value = "easy"
                +botLevelText("easy", pageLang)
            }
            option {
                value = "normal"
                +botLevelText("normal", pageLang)
            }
            option {
                value = "hard"
                +botLevelText("hard", pageLang)
            }
            option {
                value = "2"
                +botExhibitionText(pageLang)
            }
        }
    }
    label {
        +fieldSeriesText(pageLang)
        select {
            name = "wins"
            option {
                value = ""
                +seriesOffText(pageLang)
            }
            for (target in listOf(2, 3, 5)) {
                option {
                    value = target.toString()
                    +seriesTargetText(target, pageLang)
                }
            }
        }
    }
}
