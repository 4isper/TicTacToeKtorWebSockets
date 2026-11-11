package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.ui.CellView
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.boardLabel
import com.m4isper.tictactoe.ui.cellViews
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Owns the board grid. A state change updates the changed cells in place instead of
 * rebuilding the grid, so a move no longer steals keyboard focus mid-navigation, and
 * a fresh grid is built only when the board size itself changes.
 */
internal class BoardRenderer(
    private val board: HTMLElement,
    private val turnBar: HTMLElement,
    private val lang: Lang,
    private val onCellClick: (x: Int, y: Int) -> Unit,
) {
    private var cells: List<List<HTMLButtonElement>> = emptyList()
    private var views: List<CellView> = emptyList()
    private var cellPx = 0

    private val keyHandler: (Event) -> Unit = { event -> onBoardKey(event) }
    private val resizeHandler: (Event) -> Unit = { recomputeCellSize() }

    init {
        // the grid itself is a named, tabbable fallback when a move disables every cell
        board.setAttribute("role", "grid")
        board.setAttribute("aria-label", boardLabel(lang))
        board.setAttribute("tabindex", "0")
        board.addEventListener("keydown", keyHandler)
        window.addEventListener("resize", resizeHandler)
    }

    fun render(previous: GameState?, state: GameState, mySymbol: PlayerSymbol?) {
        val focused = document.activeElement
        clearHint()
        val size = state.field.size
        if (cells.size != size || cells.any { it.size != size }) rebuild(size)
        recomputeCellSize()
        val next = cellViews(previous, state, mySymbol, lang)
        next.forEachIndexed { index, view ->
            if (index >= views.size || views[index] != view) {
                apply(cells[index / size][index % size], view)
            }
        }
        views = next
        restoreFocus(focused)
    }

    /** A fresh grid (first render or a size change): every cell gets applied below. */
    private fun rebuild(size: Int) {
        board.innerHTML = ""
        cells = List(size) { y ->
            val rowDiv = document.createElement("div")
            rowDiv.className = "row"
            val row = List(size) { x ->
                val button = document.createElement("button") as HTMLButtonElement
                button.onclick = { onCellClick(x, y) }
                rowDiv.appendChild(button)
                button
            }
            board.appendChild(rowDiv)
            row
        }
        views = emptyList()
        cellPx = 0 // force recomputeCellSize to size the fresh buttons
    }

    /** The one place that sizes cells and the turn bar, on every render and resize. */
    private fun recomputeCellSize() {
        if (cells.isEmpty()) return
        val size = cells.size
        val px = cellSize(size)
        if (px == cellPx) return
        cells.forEach { row -> row.forEach { sizeButton(it, px) } }
        cellPx = px
        turnBar.style.width = "${size * px + (size - 1) * 4}px"
    }

    private fun apply(button: HTMLButtonElement, view: CellView) {
        button.className = view.cssClass
        button.setAttribute("aria-label", view.ariaLabel)
        button.textContent = view.text
        button.disabled = view.disabled
    }

    /** The suggested cell stays outlined until the next state arrives. */
    private var hintCell: Pair<Int, Int>? = null

    fun showHint(x: Int, y: Int) {
        clearHint()
        hintCell = x to y
        cells[y][x].classList.add("hint")
    }

    private fun clearHint() {
        hintCell?.let { (x, y) ->
            if (y < cells.size && x < cells[y].size) cells[y][x].classList.remove("hint")
        }
        hintCell = null
    }

    private fun sizeButton(button: HTMLButtonElement, px: Int) {
        button.style.width = "${px}px"
        button.style.height = "${px}px"
        button.style.fontSize = "${px / 2}px"
    }

    /** Eight-tenths of the smaller viewport side, capped and floored at a 44px touch target. */
    private fun cellSize(size: Int): Int = (minOf(window.innerWidth, window.innerHeight) * 0.8 / size)
        .toInt().coerceAtLeast(MIN_TOUCH_PX).coerceAtMost(MAX_CELL_PX)

    /** A state change may disable the focused cell; hand focus to an enabled neighbour. */
    private fun restoreFocus(previouslyFocused: Element?) {
        val button = previouslyFocused as? HTMLButtonElement ?: return
        if (cells.none { row -> row.any { it === button } }) return
        if (!button.disabled) return
        (cells.flatten().firstOrNull { !it.disabled } ?: board).focus()
    }

    /** Arrow keys move focus inside the grid's row/column; the replay's global keys are untouched. */
    private fun onBoardKey(event: Event) {
        val key = event.asDynamic().key as? String ?: return
        if (key !in ARROW_KEYS) return
        if (event.target === board) {
            event.preventDefault()
            event.stopPropagation()
            focusFirstCell()
            return
        }
        val target = event.target as? HTMLButtonElement ?: return
        val size = cells.size
        val from = cells.flatten().indexOf(target)
        if (from < 0) return
        val row = from / size
        val col = from % size
        val next = when (key) {
            "ArrowLeft" -> row to (col - 1)
            "ArrowRight" -> row to (col + 1)
            "ArrowUp" -> (row - 1) to col
            else -> (row + 1) to col
        }
        if (next.first !in 0 until size || next.second !in 0 until size) return
        event.preventDefault()
        event.stopPropagation()
        val cell = cells[next.first][next.second]
        if (!cell.disabled) cell.focus()
    }

    /** Arrow keys from the grid container enter the first usable cell. */
    private fun focusFirstCell() {
        val all = cells.flatten()
        (all.firstOrNull { !it.disabled } ?: all.firstOrNull())?.focus()
    }

    private companion object {
        const val MIN_TOUCH_PX = 44
        const val MAX_CELL_PX = 96
        val ARROW_KEYS = setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown")
    }
}
