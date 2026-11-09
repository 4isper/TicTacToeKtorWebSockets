package com.m4isper.tictactoe.api

import kotlinx.css.*
import kotlinx.css.properties.*

internal fun CssBuilder.styles() {
    themeStyles()
    buttonStyles()
    layoutStyles()
    scrollbarStyles()
    boardStyles()
    moveListStyles()
    createStyles()
    effectStyles()
}

/** The running move list beside the board. */
private fun CssBuilder.moveListStyles() {
    rule("#moves") {
        display = Display.flex
        flexWrap = FlexWrap.wrap
        gap = 4.px
        margin = Margin(vertical = 6.px)
        padding = Padding(horizontal = 0.px)
        listStyleType = ListStyleType.none
        maxHeight = 90.px
        overflowY = Overflow.auto
    }
    rule("#moves li") {
        backgroundColor = Color("var(--panel)")
        borderRadius = 6.px
        padding = Padding(horizontal = 8.px, vertical = 2.px)
        fontSize = 13.px
        color = Color("var(--muted)")
    }
}

private fun CssBuilder.themeStyles() {
    rule(":root") {
        put("--bg", "#14141f")
        put("--panel", "#1f1f33")
        put("--text", "#e8e8f0")
        put("--muted", "#9a9ab8")
        put("--accent", "#7aa2f7")
        put("--win", "#2e7d4f")
        put("--danger", "#ff7b7b")
        put("--placeholder", "#6a6a85")
    }
    rule("@media (prefers-color-scheme: light)") {
        // the automatic light theme spares a manually chosen dark one
        rule(":root:not([data-theme=\"dark\"])") { lightPalette() }
    }
    rule(":root[data-theme=\"light\"]") { lightPalette() }
    rule("body") {
        fontFamily = "system-ui, sans-serif"
        display = Display.flex
        flexDirection = FlexDirection.column
        alignItems = Align.center
        backgroundColor = Color("var(--bg)")
        color = Color("var(--text)")
    }
    rule("#status") {
        fontSize = 22.px
        minHeight = 30.px
    }
    rule("#error") {
        color = Color("var(--danger)")
        minHeight = 24.px
    }
    rule("#score") {
        fontSize = 18.px
        minHeight = 24.px
        color = Color("var(--muted)")
    }
    rule("#clocks") {
        display = Display.none
        fontSize = 20.px
        color = Color("var(--accent)")
    }
    rule("#copy-link") {
        margin = Margin(vertical = 8.px)
    }
}

/** The light palette, shared by the automatic (media query) and the manual (`data-theme`) selectors. */
private fun CssBuilder.lightPalette() {
    put("--bg", "#f4f4f8")
    put("--panel", "#ffffff")
    put("--text", "#1a1a24")
    put("--muted", "#6a6a85")
    put("--accent", "#4a6fd4")
    put("--win", "#2e7d4f")
    put("--danger", "#c0392b")
    put("--placeholder", "#9a9ab8")
}

private fun CssBuilder.buttonStyles() {
    rule(".secondary") {
        padding = Padding(horizontal = 14.px, vertical = 8.px)
        fontSize = 15.px
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderWidth = 0.px
        borderRadius = 8.px
        cursor = Cursor.pointer
    }
    rule(".secondary:disabled") {
        opacity = 0.6
        cursor = Cursor.default
    }
    rule("#chat-input") {
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderWidth = 0.px
        borderRadius = 8.px
        padding = Padding(horizontal = 10.px, vertical = 8.px)
        fontSize = 15.px
    }
    rule("#chat-input::placeholder") {
        color = Color("var(--placeholder)")
    }
}

private fun CssBuilder.scrollbarStyles() {
    rule("#chat-log, #moves, #board") {
        put("scrollbar-width", "thin")
        put("scrollbar-color", "var(--muted) transparent")
    }
    rule("#chat-log::-webkit-scrollbar, #moves::-webkit-scrollbar, #board::-webkit-scrollbar") {
        put("width", "8px")
        put("height", "8px")
    }
    rule("#chat-log::-webkit-scrollbar-thumb, #moves::-webkit-scrollbar-thumb, #board::-webkit-scrollbar-thumb") {
        backgroundColor = Color("var(--muted)")
        borderRadius = 4.px
    }
    rule("#chat-log::-webkit-scrollbar-thumb:hover, #moves::-webkit-scrollbar-thumb:hover, #board::-webkit-scrollbar-thumb:hover") {
        backgroundColor = Color("var(--accent)")
    }
    rule("#chat-log::-webkit-scrollbar-track, #moves::-webkit-scrollbar-track, #board::-webkit-scrollbar-track") {
        backgroundColor = Color("transparent")
    }
}

private fun CssBuilder.layoutStyles() {
    rule("#rematch, #draw, #undo, #resign, #hint") {
        display = Display.none
    }
    rule(".action-row") {
        display = Display.flex
        flexWrap = FlexWrap.wrap
        gap = 8.px
        justifyContent = JustifyContent.center
        margin = Margin(vertical = 4.px)
    }
    rule("#create, #chat, #rooms, #leaderboard, #profile") {
        width = 100.pct
        maxWidth = 560.px
    }
    nameRowStyles()
    rule("#turn-bar") {
        display = Display.none
        height = 6.px
        maxWidth = 100.pct
        backgroundColor = Color("var(--panel)")
        borderRadius = 3.px
        margin = Margin(vertical = 6.px)
        overflow = Overflow.hidden
    }
    rule("#turn-bar-fill") {
        width = 100.pct
        height = 100.pct
        backgroundColor = Color("var(--accent)")
        transition += Transition("width", 250.ms, Timing.linear)
    }
    sparklineStyles()
    rule(":focus-visible") {
        put("outline", "3px solid var(--accent)")
        put("outline-offset", "2px")
    }
    rule("@media (prefers-reduced-motion: reduce)") {
        rule(".cell.winning, .cell.pop, .reaction-bubble, #turn-bar-fill") {
            put("animation", "none")
            put("transition", "none")
        }
    }
    rule(".pwa-notice, #install-button") {
        position = Position.fixed
        put("bottom", "12px")
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        padding = Padding(horizontal = 12.px, vertical = 8.px)
        put("z-index", "50")
    }
    rule(".pwa-notice") {
        put("left", "12px")
        put("right", "12px")
        display = Display.flex
        gap = 8.px
    }
}

private fun CssBuilder.nameRowStyles() {
    rule(".name-row") {
        display = Display.flex
        gap = 4.px
        margin = Margin(vertical = 4.px)
    }
    rule("#name, #player-search") {
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderWidth = 0.px
        borderRadius = 8.px
        padding = Padding(horizontal = 10.px, vertical = 8.px)
        fontSize = 15.px
    }
    rule("#name::placeholder, #player-search::placeholder") {
        color = Color("var(--placeholder)")
    }
}

private fun CssBuilder.sparklineStyles() {
    rule(".sparkline") {
        width = 100.pct
        height = 30.px
        margin = Margin(vertical = 4.px)
    }
    rule(".sparkline polyline") {
        // the svg properties are outside kotlinx.css's vocabulary
        put("fill", "none")
        put("stroke", "var(--accent)")
        put("stroke-width", "2px")
    }
}

private fun CssBuilder.boardStyles() {
    rule(".row") {
        display = Display.flex
        gap = 4.px
    }
    rule(".cell") {
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderRadius = 8.px
        borderWidth = 0.px
        cursor = Cursor.pointer
        put("flex-shrink", "0") // rows must overflow the board box, not squeeze the touch targets
    }
    rule(".cell:disabled") {
        cursor = Cursor.default
        opacity = 0.85
    }
    rule(".cell.hint") {
        put("outline", "3px solid var(--accent)")
        put("outline-offset", "-3px")
    }
    rule(".cell.pop") {
        animation += Animation("cell-pop", 180.ms, Timing.easeOut)
    }
    rule(".cell.last-move") {
        boxSizing = BoxSizing.borderBox
        borderWidth = 2.px
        borderStyle = BorderStyle.solid
        borderColor = Color("var(--accent)")
    }
    rule(".cell.winning") {
        backgroundColor = Color("var(--win)")
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
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderRadius = 4.px
        fontSize = 13.px
        lineHeight = LineHeight("26px")
        textAlign = TextAlign.center
    }
}

/** The create-game form and the quick-play link. */
private fun CssBuilder.createStyles() {
    rule("#quick-play") {
        display = Display.inlineBlock
        margin = Margin(vertical = 6.px)
        padding = Padding(horizontal = 14.px, vertical = 8.px)
        fontSize = 15.px
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderRadius = 8.px
        textDecoration = TextDecoration.none
    }
    rule("#create") {
        margin = Margin(vertical = 12.px)
    }
    rule("#create form") {
        display = Display.flex
        flexWrap = FlexWrap.wrap
        gap = 8.px
        alignItems = Align.flexEnd
        margin = Margin(vertical = 8.px)
    }
    rule("#create label") {
        display = Display.flex
        flexDirection = FlexDirection.column
        gap = 2.px
        fontSize = 12.px
        color = Color("var(--muted)")
    }
    rule("#create input, #create select") {
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderWidth = 0.px
        borderRadius = 8.px
        padding = Padding(horizontal = 8.px, vertical = 6.px)
        fontSize = 14.px
        width = 96.px
    }
    rule("#create button") {
        padding = Padding(horizontal = 14.px, vertical = 8.px)
        fontSize = 15.px
        backgroundColor = Color("var(--panel)")
        color = Color("var(--text)")
        borderWidth = 0.px
        borderRadius = 8.px
        cursor = Cursor.pointer
    }
}

private fun CssBuilder.effectStyles() {
    rule("#board") {
        position = Position.relative
        maxWidth = 100.pct // the box stays inside the viewport so overflow-x actually scrolls
        put("overflow-x", "auto") // a 10x10 board keeps 44px touch targets on narrow screens
    }
    rule("#reactions") {
        display = Display.flex
        gap = 8.px
        margin = Margin(vertical = 6.px)
    }
    rule(".reaction") {
        backgroundColor = Color("var(--panel)")
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
