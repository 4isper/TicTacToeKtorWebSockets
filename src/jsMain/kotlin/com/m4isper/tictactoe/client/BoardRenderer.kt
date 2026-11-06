package com.m4isper.tictactoe.client

import com.m4isper.tictactoe.domain.PlayerSymbol
import com.m4isper.tictactoe.game.GameState
import com.m4isper.tictactoe.ui.CellView
import com.m4isper.tictactoe.ui.Lang
import com.m4isper.tictactoe.ui.cellViews
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement

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

    fun render(previous: GameState?, state: GameState, mySymbol: PlayerSymbol?) {
        clearHint()
        val size = state.field.size
        if (cells.size != size || cells.any { it.size != size }) rebuild(size)
        val px = cellSize(size)
        if (px != cellPx) cells.forEach { row -> row.forEach { button -> sizeButton(button, px) } }
        cellPx = px
        turnBar.style.width = "${size * px + (size - 1) * 4}px" // match the board width
        val next = cellViews(previous, state, mySymbol, lang)
        next.forEachIndexed { index, view ->
            if (index >= views.size || views[index] != view) {
                apply(cells[index / size][index % size], view)
            }
        }
        views = next
    }

    /** A fresh grid (first render or a size change): every cell gets applied below. */
    private fun rebuild(size: Int) {
        board.innerHTML = ""
        val px = cellSize(size)
        cellPx = px
        cells = List(size) { y ->
            val rowDiv = document.createElement("div")
            rowDiv.className = "row"
            val row = List(size) { x ->
                val button = document.createElement("button") as HTMLButtonElement
                sizeButton(button, px)
                button.onclick = { onCellClick(x, y) }
                rowDiv.appendChild(button)
                button
            }
            board.appendChild(rowDiv)
            row
        }
        views = emptyList()
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

    /** Eight-tenths of the smaller viewport side, capped — the formula renderBoard always used. */
    private fun cellSize(size: Int): Int = (minOf(window.innerWidth, window.innerHeight) * 0.8 / size).toInt().coerceAtMost(76)
}
