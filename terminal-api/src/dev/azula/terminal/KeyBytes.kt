package dev.azula.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Terminal control sequences, built from char codes so no invisible control
 * bytes or fragile unicode escapes live in the source. ESC = 0x1b, DEL = 0x7f.
 */
object TermSeq {
    private val ESC_CH = 0x1b.toChar()
    val ESC: String = ESC_CH.toString()
    val UP: String = "$ESC_CH[A"
    val DOWN: String = "$ESC_CH[B"
    val RIGHT: String = "$ESC_CH[C"
    val LEFT: String = "$ESC_CH[D"
    // Application-cursor-keys mode (DECCKM ?1h): apps like vi/less expect `ESC O x`.
    val APP_UP: String = "${ESC_CH}OA"
    val APP_DOWN: String = "${ESC_CH}OB"
    val APP_RIGHT: String = "${ESC_CH}OC"
    val APP_LEFT: String = "${ESC_CH}OD"
    val APP_HOME: String = "${ESC_CH}OH"
    val APP_END: String = "${ESC_CH}OF"
    val TAB: String = "\t"
    val ENTER: String = "\r"
    val BACKSPACE: String = 0x7f.toChar().toString()   // DEL — what terminals send
    val DELETE: String = "$ESC_CH[3~"
    val HOME: String = "$ESC_CH[H"
    val END: String = "$ESC_CH[F"
    val PAGE_UP: String = "$ESC_CH[5~"
    val PAGE_DOWN: String = "$ESC_CH[6~"
    val CTRL_C: String = 0x03.toChar().toString()      // ETX
}

/**
 * Translate a Compose key-down [KeyEvent] into the bytes a terminal would send to
 * a PTY. Returns null for events we don't handle (the caller then leaves the
 * event unconsumed).
 *
 * [includePrintable] is false on the mobile soft-keyboard path, where plain
 * printable characters are captured by the text field instead — there we only
 * want the *special* keys (arrows, Esc, Tab, Enter, Backspace, Ctrl-combos) from
 * a hardware keyboard, so printable keys fall through and aren't double-sent.
 *
 * [includeBackspace] is likewise false on the mobile smart-input path: there
 * Backspace must NOT be consumed here, so it falls through into the text
 * field's own `onValueChange` and is captured by the stable-buffer diff (see
 * `MobileRawTerminalInput`) instead of being double-sent.
 */
fun keyEventToBytes(ev: KeyEvent, includePrintable: Boolean = true, includeBackspace: Boolean = true, appCursorKeys: Boolean = false): String? {
    if (ev.type != KeyEventType.KeyDown) return null
    return encodeKey(
        key = ev.key,
        codePoint = ev.utf16CodePoint,
        ctrl = ev.isCtrlPressed,
        alt = ev.isAltPressed,
        includePrintable = includePrintable,
        includeBackspace = includeBackspace,
        appCursorKeys = appCursorKeys,
    )
}

/**
 * The pure key-translation decision behind [keyEventToBytes], split out so it
 * can be tested from common code: a Compose `KeyEvent` wraps a *platform* event
 * type and cannot be constructed off-platform, which is exactly why the Ctrl
 * path had no coverage and shipped broken -- no Ctrl-combination reached the PTY
 * on desktop at all.
 *
 * [codePoint] is the character the platform composed for this chord, which is
 * deliberately *not* trusted for modifier handling:
 *
 * - **Ctrl** resolves from [key], not [codePoint]. AWT/Skiko report a
 *   Ctrl-chord's code point as the control character itself (`Ctrl-C` → 0x03),
 *   so the old `codePoint.toChar().isLetter()` test was false for every letter
 *   chord and fell through to null. Reading [key] is also simply more direct --
 *   the byte depends on which key was struck, not on what the OS composed.
 * - **Alt** is a Meta prefix: `ESC` followed by whatever the key would produce
 *   unmodified. The composed character is discarded, because macOS has already
 *   turned `Option-f` into `ƒ` by the time the event arrives and sending that
 *   glyph is the "Option drops characters into the terminal" bug.
 *
 * See `openspec/specs/terminal/spec.md`, "Key Encoding".
 */
fun encodeKey(
    key: Key,
    codePoint: Int,
    ctrl: Boolean = false,
    alt: Boolean = false,
    includePrintable: Boolean = true,
    includeBackspace: Boolean = true,
    appCursorKeys: Boolean = false,
): String? {
    // Alt wraps whatever the unmodified chord resolves to, so named keys compose
    // too (Alt-Left → ESC + the arrow sequence) and the two encodings can't
    // drift. Ctrl resolves first and Alt wraps it, matching xterm's Ctrl-Alt-C
    // → ESC + 0x03.
    if (alt) {
        val base = encodeKey(key, altBaseCodePoint(key, codePoint), ctrl, alt = false, includePrintable, includeBackspace, appCursorKeys)
        return base?.let { TermSeq.ESC + it }
    }

    if (ctrl) return ctrlBytesFor(key, codePoint)

    when (key) {
        Key.DirectionUp -> return if (appCursorKeys) TermSeq.APP_UP else TermSeq.UP
        Key.DirectionDown -> return if (appCursorKeys) TermSeq.APP_DOWN else TermSeq.DOWN
        Key.DirectionRight -> return if (appCursorKeys) TermSeq.APP_RIGHT else TermSeq.RIGHT
        Key.DirectionLeft -> return if (appCursorKeys) TermSeq.APP_LEFT else TermSeq.LEFT
        Key.Enter, Key.NumPadEnter -> return TermSeq.ENTER
        Key.Tab -> return TermSeq.TAB
        Key.Escape -> return TermSeq.ESC
        Key.Backspace -> if (includeBackspace) return TermSeq.BACKSPACE else return null
        Key.Delete -> return TermSeq.DELETE
        Key.MoveHome -> return if (appCursorKeys) TermSeq.APP_HOME else TermSeq.HOME
        Key.MoveEnd -> return if (appCursorKeys) TermSeq.APP_END else TermSeq.END
        Key.PageUp -> return TermSeq.PAGE_UP
        Key.PageDown -> return TermSeq.PAGE_DOWN
    }

    if (includePrintable && codePoint >= 0x20 && codePoint != 0x7f) return codePointToString(codePoint)
    return null
}

/**
 * Ctrl-chord → control byte, resolved from [key] with [codePoint] only as a
 * fallback. Letters mask to 1..26; the four specials the Key Encoding
 * requirement names get their own bytes.
 *
 * The [codePoint] fallback covers a platform that reports the *base* character
 * for a Ctrl-chord rather than the control character, and the already-control
 * case for anything not enumerated here. Neither is the primary path -- keeping
 * it costs one branch and means a platform reporting either shape works.
 */
private fun ctrlBytesFor(key: Key, codePoint: Int): String? {
    BASE_CHARS[key]?.let { if (it in 'a'..'z') return ctrl(it) }
    when (key) {
        Key.LeftBracket -> return 0x1b.toChar().toString()   // Ctrl-[  → ESC
        Key.Backslash -> return 0x1c.toChar().toString()     // Ctrl-\  → FS
        Key.RightBracket -> return 0x1d.toChar().toString()  // Ctrl-]  → GS
        Key.Spacebar, Key.At -> return 0x00.toChar().toString() // Ctrl-Space / Ctrl-@ → NUL
    }
    // Fallbacks: a base letter the platform didn't mask, or an already-masked
    // control byte for a key not enumerated above.
    val c = codePoint.toChar()
    if (c.isLetter()) return ctrl(c)
    if (codePoint in 0x01..0x1f) return c.toString()
    return null
}

/**
 * The unmodified character each key produces, keyed by [Key] so neither Ctrl nor
 * Alt has to trust what the platform composed. Built once rather than per
 * keystroke.
 */
private val BASE_CHARS: Map<Key, Char> = buildMap {
    val letters = listOf(
        Key.A to 'a', Key.B to 'b', Key.C to 'c', Key.D to 'd', Key.E to 'e', Key.F to 'f',
        Key.G to 'g', Key.H to 'h', Key.I to 'i', Key.J to 'j', Key.K to 'k', Key.L to 'l',
        Key.M to 'm', Key.N to 'n', Key.O to 'o', Key.P to 'p', Key.Q to 'q', Key.R to 'r',
        Key.S to 's', Key.T to 't', Key.U to 'u', Key.V to 'v', Key.W to 'w', Key.X to 'x',
        Key.Y to 'y', Key.Z to 'z',
    )
    val digits = listOf(
        Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
        Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    )
    putAll(letters)
    putAll(digits)
}

/**
 * The code point an Alt chord should encode *as*, ignoring whatever the OS
 * composed. macOS turns `Option-f` into `ƒ` before the event ever arrives, so
 * recursing on the raw [codePoint] would send `ESC ƒ` -- the ESC prefix right
 * and the character wrong.
 *
 * Falls back to [codePoint] only when it is plain ASCII, which lets `Alt-=` and
 * friends work on platforms that don't compose, while a composed glyph (almost
 * always non-ASCII) resolves to 0 and leaves the chord unhandled rather than
 * sending bytes known to be wrong.
 */
private fun altBaseCodePoint(key: Key, codePoint: Int): Int =
    BASE_CHARS[key]?.code ?: if (codePoint in 0x20..0x7e) codePoint else 0

/** The control byte for Ctrl+[letter], e.g. ctrl('c') == ETX (0x03). */
fun ctrl(letter: Char): String = ((letter.lowercaseChar().code) and 0x1f).toChar().toString()

/** Codepoint → String, multiplatform (surrogate pair for supplementary planes). */
fun codePointToString(cp: Int): String =
    if (cp <= 0xFFFF) cp.toChar().toString()
    else {
        val c = cp - 0x10000
        charArrayOf((0xD800 + (c shr 10)).toChar(), (0xDC00 + (c and 0x3FF)).toChar()).concatToString()
    }
