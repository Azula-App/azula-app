package dev.azula.terminal

/**
 * A text selection anchored to absolute row indices (see [TermFrame.totalScrolled]),
 * not visible-list positions -- so it survives new output scrolling the view and
 * only needs invalidating when an endpoint is evicted past the scrollback cap (see
 * `rowAt`) or the screen changes underneath it (alt toggle / clear / conv switch,
 * handled by the UI via `TermFrame.discontinuityEpoch`). [startAbs]/[startCol] is
 * where the drag began; [endAbs]/[endCol] is the live end -- order-independent,
 * use [normalized] before extracting text.
 */
data class TermSelection(val startAbs: Long, val startCol: Int, val endAbs: Long, val endCol: Int) {
    /** This selection with its endpoints ordered start <= end (row-major, then column). */
    fun normalized(): TermSelection =
        if (startAbs < endAbs || (startAbs == endAbs && startCol <= endCol)) this
        else TermSelection(endAbs, endCol, startAbs, startCol)
}

/**
 * The row at absolute index [abs] (scrollback followed by the live grid; see
 * [TermFrame.totalScrolled]), or null if [abs] has scrolled off past the
 * scrollback cap, or is beyond the live grid. Stable as rows migrate from grid
 * into scrollback -- a row's abs index never changes over its lifetime.
 */
fun TermFrame.rowAt(abs: Long): Row? {
    val firstScrollbackAbs = totalScrolled - scrollback.size
    val firstGridAbs = totalScrolled
    return when {
        abs < firstScrollbackAbs -> null
        abs < firstGridAbs -> scrollback[(abs - firstScrollbackAbs).toInt()]
        abs < firstGridAbs + grid.size -> grid[(abs - firstGridAbs).toInt()]
        else -> null
    }
}

/** Plain text of every row currently on screen (the live grid only, no
 *  scrollback), one line per row, trailing blanks trimmed per row. */
fun TermFrame.screenText(): String = grid.joinToString("\n") { it.text() }

/** Plain text of the whole retained buffer: scrollback followed by the live
 *  screen, one line per row. */
fun TermFrame.allText(): String = (scrollback + grid).joinToString("\n") { it.text() }

/**
 * Extract the plain text spanned by [sel] (normalized internally): interior
 * rows contribute their full (trimmed) [Row.text], while the first and last
 * rows are column-clipped to the selection's exact bounds via [Row.text]'s
 * ranged overload. Rows that have scrolled off past the cap (see [rowAt]) are
 * skipped rather than aborting the whole extraction.
 */
fun TermFrame.textInSelection(sel: TermSelection): String {
    val n = sel.normalized()
    val sb = StringBuilder()
    var abs = n.startAbs
    var firstLine = true
    while (abs <= n.endAbs) {
        val row = rowAt(abs)
        if (row != null) {
            val text = when {
                n.startAbs == n.endAbs -> row.text(n.startCol, n.endCol + 1)
                abs == n.startAbs -> row.text(n.startCol, row.cols)
                abs == n.endAbs -> row.text(0, n.endCol + 1)
                else -> row.text()
            }
            if (!firstLine) sb.append('\n')
            sb.append(text)
            firstLine = false
        }
        abs++
    }
    return sb.toString()
}
