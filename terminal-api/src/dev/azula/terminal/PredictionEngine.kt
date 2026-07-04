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
 *  - On the **primary** screen: printable characters, backspace, and left/right
 *    arrows are predicted; homogeneous multi-char strings (an autocorrect
 *    backspace-run, or a swiped/pasted run of printables) are predicted
 *    char-by-char -- the input layer only ever sends one homogeneous string
 *    per call (see `InputDiff`/`MobileRawTerminalInput`'s two-call rule), so a
 *    mixed string is unexpected and just pauses like any other unrecognized
 *    input. Enter, Tab, Ctrl-keys, Escape, and up/down flush the overlay and
 *    pause until the server catches up.
 *  - Reconciliation: server output that contains any non-SGR escape (cursor moves,
 *    erases, alt-screen) flushes everything; otherwise each pending glyph is
 *    confirmed when the real cell matches, flushed on a mismatch, and dropped after
 *    a [timeoutMs] timeout if never echoed.
 *  - On the **alternate** screen (full-screen TUIs, which echo unpredictably):
 *    predictions are far more conservative -- only printables/backspace (never
 *    arrows), only once [altQuietMs] have passed since the last server output
 *    (so a burst of app-driven repaint doesn't get raced), capped at
 *    [ALT_MAX_PENDING] pending glyphs, flushed instantly on ANY server output
 *    while on the alt screen, and dropped after [altTimeoutMs] if never
 *    reconciled. A primary<->alt flip flushes everything.
 */
class PredictionEngine(
    private val emu: TerminalEmulator,
    var enabled: Boolean = true,
    private val timeoutMs: Long = 1000,
    private val altQuietMs: Long = 200,
    private val altTimeoutMs: Long = 400,
    private val now: () -> Long = { defaultClock.elapsedNow().inWholeMilliseconds },
) : TerminalPredictor {

    private data class Pred(val row: Int, val col: Int, val cp: Int, val createdAt: Long)

    private val overlays = ArrayList<Pred>()
    private var predRow = 0
    private var predCol = 0
    private var paused = false

    // Backspaces that stepped predCol back over a REAL (already-confirmed) cell
    // rather than an overlay -- i.e. a locally-predicted erase whose real echo
    // hasn't landed yet. `overlays` alone can't represent this: it goes empty
    // the moment the erased cell had no pending overlay, even though predCol
    // has diverged from the live cursor and isn't safe to resync from until the
    // server actually catches up (see `ensureSynced`).
    private var pendingDeletes = 0

    // Alt-screen bookkeeping: when the last server output landed (gates the
    // quiet window) and which screen we were on last time output arrived (so a
    // primary<->alt flip -- detected the moment it shows up in onServerOutput --
    // flushes rather than being reconciled against the wrong screen).
    private var lastOutputAt: Long = Long.MIN_VALUE / 2
    private var wasAltScreen = false

    override fun onInput(bytes: String) {
        if (!enabled) return
        if (emu.liveAltScreen) { onAltInput(bytes); emu.repaint(); return }

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

    /** Conservative alt-screen prediction: printables/backspace only, gated by
     *  the quiet window and the pending cap; see the class doc. */
    private fun onAltInput(bytes: String) {
        val t = now()
        if (overlays.isNotEmpty() && t - overlays.first().createdAt > altTimeoutMs) flush()
        if (t - lastOutputAt <= altQuietMs) return

        if (bytes.length == 1) {
            val code = bytes[0].code
            when {
                code >= 0x20 && code != 0x7f -> predictAltPrintable(code)
                code == 0x08 || code == 0x7f -> predictBackspace()
            }
        } else when {
            isHomogeneousPrintable(bytes) -> bytes.forEach { predictAltPrintable(it.code) }
            isHomogeneousBackspace(bytes) -> repeat(bytes.length) { predictBackspace() }
            // arrows/other escape sequences: never predicted on the alt screen.
        }
    }

    private fun predictAltPrintable(code: Int) {
        if (overlays.size >= ALT_MAX_PENDING) return
        predictPrintable(code)
    }

    override fun onServerOutput(chunk: String) {
        val t = now()
        lastOutputAt = t
        val flipped = emu.liveAltScreen != wasAltScreen
        wasAltScreen = emu.liveAltScreen
        if (flipped) { flush(); paused = false; return }
        if (emu.liveAltScreen) {
            // alt screen: ANY output flushes, no reconciliation.
            if (overlays.isNotEmpty()) flush() else pendingDeletes = 0
            return
        }

        // ---- primary screen: unchanged from before alt-screen prediction ----
        paused = false
        // Bytes we've sent so far are processed by the remote shell in order,
        // so any new output means everything sent before it -- including
        // pending backspaces -- has already been reflected in the live grid.
        pendingDeletes = 0
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

        if (overlays.any { t - it.createdAt > timeoutMs }) { flush(); return }
        if (overlays.isEmpty()) syncCursor()
    }

    override fun overlays(): List<PredictedCell> = overlays.map { PredictedCell(it.row, it.col, it.cp) }

    override fun cursor(): Pair<Int, Int>? = if (overlays.isEmpty()) null else predRow to predCol

    override fun reset() { overlays.clear(); pendingDeletes = 0; paused = false; syncCursor() }

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
            val hadOverlay = overlays.removeAll { it.row == predRow && it.col == predCol }
            if (!hadOverlay) pendingDeletes++  // erased a real cell -- predCol is now ahead of the live cursor
        }
    }

    private fun predictArrow(d: Int) {
        ensureSynced()
        val target = predCol + d
        val bound = maxOf(emu.liveRowLength(predRow), emu.liveCursorCol)
        if (target in 0..bound) predCol = target
    }

    // Only safe to snap predCol/predRow to the live cursor when nothing we've
    // locally predicted is still ahead of what the server has confirmed --
    // that means no pending overlays AND no pending erases (see
    // `pendingDeletes`), otherwise a same-length backspace+retype fix-up
    // (two separate onInput calls) would have its retyped char resynced back
    // to the pre-backspace cursor column, stranding it one cell past the real
    // text instead of over the character being replaced.
    private fun ensureSynced() { if (overlays.isEmpty() && pendingDeletes == 0) syncCursor() }
    private fun syncCursor() { predRow = emu.liveCursorRow; predCol = emu.liveCursorCol }
    private fun pauseAndFlush() { flush(); paused = true }
    private fun flush() { overlays.clear(); pendingDeletes = 0; syncCursor() }

    private companion object {
        const val SPACE = 0x20
        const val ALT_MAX_PENDING = 8
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
