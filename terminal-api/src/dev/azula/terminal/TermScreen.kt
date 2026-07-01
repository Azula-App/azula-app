package dev.azula.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A run of terminal text sharing one foreground color + weight. [color] is an
 *  ARGB Long, or null for the default (theme) color. */
data class TermSpan(val text: String, val color: Long?, val bold: Boolean)

/**
 * A minimal VT-100 / ANSI terminal screen model backed by Compose snapshot state.
 * Handles plain text, carriage-return overwrite, backspace, tab expansion, SGR
 * colors/bold, and enough erase/reset sequences to be usable with typical shell
 * prompts and common CLI tools.
 *
 * NOTE: Full-screen TUI applications (vim, top, htop, nano, …) that rely on
 * alternate-screen, complex cursor addressing, or terminal resize are intentionally
 * out of scope. The model is designed for interactive shell I/O.
 */
class TermScreen {
    /** Committed lines, each a list of colored spans (capped at ~5000). */
    val rows = mutableStateListOf<List<TermSpan>>()

    /** The in-progress (current) line as colored spans. */
    var current by mutableStateOf<List<TermSpan>>(emptyList())
        private set

    /** The current line split at the cursor column, so a caret can be drawn at the
     *  real cursor position (mid-line) rather than always at the line end. */
    var currentBeforeCursor by mutableStateOf<List<TermSpan>>(emptyList())
        private set
    var currentAfterCursor by mutableStateOf<List<TermSpan>>(emptyList())
        private set

    /** Plain-text views (for tests / search). */
    val rowsText: List<String> get() = rows.map { spansText(it) }
    val currentText: String get() = spansText(current)
    /** Cursor column within the current line (0-based). */
    val cursorCol: Int get() = col

    private class Cell(var ch: Char, var fg: Long?, var bold: Boolean)

    private val line = ArrayList<Cell>()
    private var col = 0
    private var curFg: Long? = null
    private var curBold = false

    /** Full reset: clear all rows, the current line, and pen attributes. */
    fun clear() {
        rows.clear()
        line.clear()
        col = 0
        curFg = null
        curBold = false
        current = emptyList()
        currentBeforeCursor = emptyList()
        currentAfterCursor = emptyList()
    }

    /**
     * Feed a chunk of terminal output. ANSI/VT escape sequences are processed
     * inline; newlines commit the current line to [rows]. [current] is refreshed
     * after processing.
     */
    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code == 0x1b -> { // ESC
                    i = handleEscape(text, i + 1)
                    continue
                }
                c == '\n' -> commitLine()
                c == '\r' -> col = 0
                c == '\b' -> { if (col > 0) col-- }
                c == '\t' -> {
                    val next = (col / 8 + 1) * 8
                    while (col < next) putChar(' ')
                }
                c.code == 0x07 -> { /* BEL — ignore */ }
                c.code < 32 -> { /* other control — ignore */ }
                else -> putChar(c)
            }
            i++
        }
        current = spansOf(line)
        val c = col.coerceIn(0, line.size)
        currentBeforeCursor = spansOf(line.subList(0, c))
        currentAfterCursor = spansOf(line.subList(c, line.size))
    }

    // ---- private helpers ----

    private fun commitLine() {
        rows.add(spansOf(line))
        if (rows.size > 5000) rows.removeAt(0)
        line.clear()
        col = 0
    }

    /** Write [c] at [col] with the current pen, padding with spaces, then advance. */
    private fun putChar(c: Char) {
        when {
            col < line.size -> line[col].let { it.ch = c; it.fg = curFg; it.bold = curBold }
            col > line.size -> {
                repeat(col - line.size) { line.add(Cell(' ', null, false)) }
                line.add(Cell(c, curFg, curBold))
            }
            else -> line.add(Cell(c, curFg, curBold))
        }
        col++
    }

    private fun spansOf(cells: List<Cell>): List<TermSpan> {
        if (cells.isEmpty()) return emptyList()
        val out = ArrayList<TermSpan>()
        val buf = StringBuilder()
        var fg = cells[0].fg
        var bold = cells[0].bold
        for (cell in cells) {
            if (cell.fg != fg || cell.bold != bold) {
                out.add(TermSpan(buf.toString(), fg, bold))
                buf.setLength(0)
                fg = cell.fg
                bold = cell.bold
            }
            buf.append(cell.ch)
        }
        out.add(TermSpan(buf.toString(), fg, bold))
        return out
    }

    /**
     * Process an escape sequence starting at [start] (just after ESC). Returns the
     * index after the complete sequence.
     *   ESC c           — full reset
     *   ESC [ … <final> — CSI: 'm' SGR (colors/bold), 'J' erase display, 'K' erase
     *                     to EOL; other finals (cursor moves) ignored
     *   ESC ] …         — OSC: skip to BEL / ST
     */
    private fun handleEscape(text: String, start: Int): Int {
        if (start >= text.length) return start
        return when (text[start]) {
            'c' -> { clear(); start + 1 }
            '[' -> {
                var j = start + 1
                val paramStart = j
                while (j < text.length && text[j].code in 0x20..0x3F) j++
                val param = text.substring(paramStart, j)
                val final = if (j < text.length) text[j] else ' '
                when (final) {
                    'm' -> applySgr(param)
                    'J' -> if (param == "" || param == "0" || param == "2" || param == "3") clear()
                    'K' -> while (line.size > col) line.removeAt(line.size - 1)
                    else -> { /* cursor moves, etc. — ignored */ }
                }
                if (j < text.length) j + 1 else j
            }
            ']' -> {
                var j = start + 1
                while (j < text.length) {
                    if (text[j].code == 0x07) { j++; break } // BEL
                    if (text[j].code == 0x1b && j + 1 < text.length && text[j + 1] == '\\') { j += 2; break } // ST
                    j++
                }
                j
            }
            else -> start + 1
        }
    }

    /** Apply an SGR (`CSI … m`) sequence to the pen. */
    private fun applySgr(param: String) {
        val codes = if (param.isEmpty()) listOf(0) else param.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> { curFg = null; curBold = false }
                1 -> curBold = true
                22 -> curBold = false
                in 30..37 -> curFg = ANSI16[code - 30]
                39 -> curFg = null
                in 90..97 -> curFg = ANSI16[8 + code - 90]
                38 -> { // extended fg
                    if (i + 1 < codes.size) when (codes[i + 1]) {
                        5 -> if (i + 2 < codes.size) { curFg = xterm256(codes[i + 2]); i += 2 }
                        2 -> if (i + 4 < codes.size) { curFg = rgb(codes[i + 2], codes[i + 3], codes[i + 4]); i += 4 }
                    }
                }
                // background (40–47/49/100–107/48) and other attrs ignored for now.
            }
            i++
        }
    }

    private companion object {
        // A One-Dark-ish 16-color palette (black shown as gray so it stays visible).
        val ANSI16 = longArrayOf(
            0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B,
            0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
            0xFF7F848E, 0xFFFF6E6E, 0xFF7BE06B, 0xFFFFD267,
            0xFF6FB7FF, 0xFFD98BFF, 0xFF66E0E0, 0xFFF3EEF1,
        )

        fun rgb(r: Int, g: Int, b: Int): Long =
            0xFF000000L or ((r.toLong() and 0xFF) shl 16) or ((g.toLong() and 0xFF) shl 8) or (b.toLong() and 0xFF)

        fun cubeLevel(i: Int) = if (i == 0) 0 else 55 + i * 40

        fun xterm256(n: Int): Long = when (n) {
            in 0..15 -> ANSI16[n]
            in 16..231 -> {
                val v = n - 16
                rgb(cubeLevel((v / 36) % 6), cubeLevel((v / 6) % 6), cubeLevel(v % 6))
            }
            in 232..255 -> { val g = 8 + (n - 232) * 10; rgb(g, g, g) }
            else -> ANSI16[7]
        }
    }
}

private fun spansText(spans: List<TermSpan>): String =
    spans.joinToString("") { it.text }
