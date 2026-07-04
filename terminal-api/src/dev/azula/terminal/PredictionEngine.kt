package dev.azula.terminal

import kotlin.time.TimeSource

/**
 * Speculative local echo — a simplified, correct-by-default take on mosh's
 * predictive echo. When you type at a shell prompt, the character is shown
 * immediately (dim + underlined) instead of waiting a full round-trip for the
 * server to echo it back; the prediction is then reconciled against real output.
 *
 * The overlay never mutates the real grid — it's merged into the published frame
 * only for rendering — so a wrong guess can never corrupt the screen; the worst
 * case is a tentative glyph that briefly shows, then snaps to the server's truth.
 *
 * Predictions are deliberately conservative:
 *  - Only on the **primary** screen. Any input while a full-screen app is on the
 *    alternate screen flushes and predicts nothing (TUIs echo unpredictably).
 *  - Printable characters, backspace, and left/right arrows are predicted;
 *    homogeneous multi-char strings (an autocorrect backspace-run, or a
 *    swiped/pasted run of printables) are predicted char-by-char -- the input
 *    layer only ever sends one homogeneous string per call (see
 *    `InputDiff`/`MobileRawTerminalInput`'s two-call rule), so a mixed string
 *    is unexpected and just pauses like any other unrecognized input. Enter,
 *    Tab, Ctrl-keys, Escape, and up/down flush the overlay and pause until the
 *    server catches up.
 *  - Reconciliation: server output that contains any non-SGR escape (cursor moves,
 *    erases, alt-screen) flushes everything; otherwise each pending glyph is
 *    confirmed when the real cell matches, flushed on a mismatch, and dropped after
 *    a [timeoutMs] timeout if never echoed.
 */
class PredictionEngine(
    private val emu: TerminalEmulator,
    var enabled: Boolean = true,
    private val timeoutMs: Long = 1000,
    private val now: () -> Long = { defaultClock.elapsedNow().inWholeMilliseconds },
) : TerminalPredictor {

    private data class Pred(val row: Int, val col: Int, val cp: Int, val createdAt: Long)

    private val overlays = ArrayList<Pred>()
    private var predRow = 0
    private var predCol = 0
    private var paused = false

    override fun onInput(bytes: String) {
        if (!enabled) return
        if (emu.liveAltScreen) { flush(); return }

        if (bytes.length == 1) {
            val code = bytes[0].code
            when {
                code >= 0x20 && code != 0x7f -> { if (!paused) predictPrintable(code) }
                code == 0x08 || code == 0x7f -> { if (!paused) predictBackspace() }
                else -> pauseAndFlush()   // Enter (CR), Tab, Ctrl-*, NUL, …
            }
        } else when {
            bytes == TermSeq.LEFT || bytes == TermSeq.APP_LEFT -> { if (!paused) predictArrow(-1) }
            bytes == TermSeq.RIGHT || bytes == TermSeq.APP_RIGHT -> { if (!paused) predictArrow(+1) }
            isHomogeneousPrintable(bytes) -> { if (!paused) bytes.forEach { predictPrintable(it.code) } }
            isHomogeneousBackspace(bytes) -> { if (!paused) repeat(bytes.length) { predictBackspace() } }
            else -> pauseAndFlush()        // up/down/home/end, escape sequences, mixed/unexpected
        }
        emu.repaint()
    }

    override fun onServerOutput(chunk: String) {
        paused = false
        if (overlays.isEmpty()) return
        if (hasDisruptiveEscape(chunk)) { flush(); return }

        // Server bytes were already applied to the grid before this call.
        val kept = ArrayList<Pred>()
        var mismatch = false
        for (p in overlays) {
            val real = emu.liveCellCp(p.row, p.col)
            when {
                real == p.cp -> {}                          // confirmed → now real, drop overlay
                real != SPACE && real != 0 -> mismatch = true // a different glyph landed here
                else -> kept.add(p)                          // not echoed yet → keep pending
            }
        }
        if (mismatch) { flush(); return }
        overlays.clear(); overlays.addAll(kept)

        val t = now()
        if (overlays.any { t - it.createdAt > timeoutMs }) { flush(); return }
        if (overlays.isEmpty()) syncCursor()
    }

    override fun overlays(): List<PredictedCell> = overlays.map { PredictedCell(it.row, it.col, it.cp) }

    override fun cursor(): Pair<Int, Int>? = if (overlays.isEmpty()) null else predRow to predCol

    override fun reset() { overlays.clear(); paused = false; syncCursor() }

    // ---- prediction ----

    private fun predictPrintable(code: Int) {
        ensureSynced()
        if (predCol >= emu.liveCols) return   // don't speculate a line wrap
        overlays.add(Pred(predRow, predCol, code, now()))
        predCol++
    }

    private fun predictBackspace() {
        ensureSynced()
        if (predCol > 0) {
            predCol--
            overlays.removeAll { it.row == predRow && it.col == predCol }
        }
    }

    private fun predictArrow(d: Int) {
        ensureSynced()
        val target = predCol + d
        val bound = maxOf(emu.liveRowLength(predRow), emu.liveCursorCol)
        if (target in 0..bound) predCol = target
    }

    private fun ensureSynced() { if (overlays.isEmpty()) syncCursor() }
    private fun syncCursor() { predRow = emu.liveCursorRow; predCol = emu.liveCursorCol }
    private fun pauseAndFlush() { flush(); paused = true }
    private fun flush() { overlays.clear(); syncCursor() }

    private companion object {
        const val SPACE = 0x20
        val defaultClock = TimeSource.Monotonic.markNow()

        /** True if [s] carries any escape that isn't a pure `ESC[…m` SGR — i.e. cursor
         *  addressing, erases, scrolls, alt-screen, OSC, etc. Predictions can only
         *  safely survive plain echo-like output. */
        fun hasDisruptiveEscape(s: String): Boolean {
            var i = s.indexOf('\u001b')
            while (i >= 0) {
                if (i + 1 >= s.length || s[i + 1] != '[') return true // ESC 7/8/M/D/(, OSC …
                var j = i + 2
                while (j < s.length && s[j].code in 0x20..0x3f) j++
                if (j >= s.length || s[j] != 'm') return true         // any non-SGR CSI
                i = s.indexOf('\u001b', j + 1)
            }
            return false
        }

        /** True if every char is an ordinary printable (no control/DEL codes) — the
         *  shape `MobileRawTerminalInput` sends for a swiped word, a pasted-looking
         *  suffix, or an autocorrect's replacement text. */
        fun isHomogeneousPrintable(s: String): Boolean = s.isNotEmpty() && s.all { it.code >= 0x20 && it.code != 0x7f }

        /** True if every char is a backspace (DEL or BS) — the shape sent for an
         *  autocorrect fix-up's deletion run. */
        fun isHomogeneousBackspace(s: String): Boolean = s.isNotEmpty() && s.all { it.code == 0x08 || it.code == 0x7f }
    }
}
