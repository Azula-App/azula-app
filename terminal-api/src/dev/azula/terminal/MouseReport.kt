package dev.azula.terminal

/**
 * Which xterm mouse-tracking DECSET mode is currently active (`?1000`/`1002`/
 * `1003`), `OFF` if none. [TerminalEmulator.mouseTrackingMode] surfaces this so
 * the UI knows whether/what to report -- see `TerminalEmulator.setModes` for how
 * DECSET/DECRST flip it, and azula-docs/docs/tech-debt.md §6 ("Mouse reporting")
 * for the feature this unblocks.
 *
 * `BUTTON_EVENT`/`ANY_EVENT` are surfaced (so a caller can tell them apart from
 * `NORMAL`) but the UI only actually reports press/release/click/wheel today --
 * continuous drag/hover motion reporting (what these two modes add over
 * `NORMAL`) is a deferred follow-up (see `terminal-real/.../Terminal.kt`'s
 * mouse-reporting doc comment).
 */
enum class MouseTrackingMode { OFF, NORMAL, BUTTON_EVENT, ANY_EVENT }

/**
 * A mouse button/wheel-notch, for [encodeMouseReport]'s `Cb` byte. [WHEEL_UP]/
 * [WHEEL_DOWN] model the wheel notches xterm reports as synthetic "buttons" 4/5
 * (`Cb` 64/65) -- there is no press/release pair for a wheel notch, just one
 * report per notch.
 */
enum class MouseButton(internal val code: Int) {
    LEFT(0), MIDDLE(1), RIGHT(2), WHEEL_UP(64), WHEEL_DOWN(65),
}

private fun MouseButton.isWheel() = this == MouseButton.WHEEL_UP || this == MouseButton.WHEEL_DOWN

private val ESC_CH = 0x1b.toChar()

/**
 * Encodes one mouse event into the bytes xterm mouse-tracking modes expect on
 * the wire, honoring [sgr] (`?1006`, extended coordinates) vs. legacy X10.
 * [col]/[row] are 1-based grid coordinates (the wire format's origin -- column/
 * row 1 is the top-left cell).
 *
 * - **SGR** (`ESC [ < Cb ; Cx ; Cy M` for press/motion, the same with a
 *   lowercase final `m` for release): arbitrary terminal sizes, unambiguous
 *   press vs. release, and the real button id is still known on release.
 *   Prioritized per azula-docs/docs/tech-debt.md §6 -- this is what
 *   `TerminalView` actually sends when `?1006` is enabled.
 * - **Legacy X10** (`ESC [ M Cb Cx Cy`, one byte each, value+32): the fallback
 *   when `?1006` isn't enabled. Only correct for `col`/`row` up to 223 (a byte
 *   can't encode past `255-32`) -- clamped rather than corrupting the stream on
 *   a huge terminal -- and a release has no real button id (xterm's fixed
 *   "released" code, 3, used here too).
 *
 * [motion] marks a drag/motion report (`?1002`/`?1003`) -- adds the `+32` motion
 * flag to `Cb` in both encodings; ignored for a wheel notch (never a motion
 * report). Modifier keys (shift/ctrl/meta) are not modeled -- always reported
 * as unmodified.
 */
fun encodeMouseReport(button: MouseButton, col: Int, row: Int, pressed: Boolean, sgr: Boolean, motion: Boolean = false): String {
    val wheel = button.isWheel()
    val cb = button.code + (if (motion && !wheel) 32 else 0)
    return if (sgr) {
        val final = if (pressed || wheel) 'M' else 'm'
        "$ESC_CH[<$cb;$col;$row$final"
    } else {
        val clampedCol = col.coerceIn(1, 223)
        val clampedRow = row.coerceIn(1, 223)
        val cbByte = if (!pressed && !wheel) 3 else cb // X10 release has no button id of its own
        "$ESC_CH[M" + (cbByte + 32).toChar() + (clampedCol + 32).toChar() + (clampedRow + 32).toChar()
    }
}
