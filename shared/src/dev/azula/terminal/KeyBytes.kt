package dev.azula.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
 */
fun keyEventToBytes(ev: KeyEvent, includePrintable: Boolean = true): String? {
    if (ev.type != KeyEventType.KeyDown) return null

    when (ev.key) {
        Key.DirectionUp -> return TermSeq.UP
        Key.DirectionDown -> return TermSeq.DOWN
        Key.DirectionRight -> return TermSeq.RIGHT
        Key.DirectionLeft -> return TermSeq.LEFT
        Key.Enter, Key.NumPadEnter -> return TermSeq.ENTER
        Key.Tab -> return TermSeq.TAB
        Key.Escape -> return TermSeq.ESC
        Key.Backspace -> return TermSeq.BACKSPACE
        Key.Delete -> return TermSeq.DELETE
        Key.MoveHome -> return TermSeq.HOME
        Key.MoveEnd -> return TermSeq.END
        Key.PageUp -> return TermSeq.PAGE_UP
        Key.PageDown -> return TermSeq.PAGE_DOWN
    }

    val cp = ev.utf16CodePoint
    if (ev.isCtrlPressed) {
        val c = cp.toChar()
        if (c.isLetter()) return ((c.lowercaseChar().code) and 0x1f).toChar().toString()
        return when (c) {
            '[' -> 0x1b.toChar().toString()   // Ctrl-[  → ESC
            '\\' -> 0x1c.toChar().toString()  // Ctrl-\  → FS
            ']' -> 0x1d.toChar().toString()   // Ctrl-]  → GS
            ' ', '@' -> 0x00.toChar().toString() // Ctrl-Space / Ctrl-@ → NUL
            else -> null
        }
    }

    if (includePrintable && cp >= 0x20 && cp != 0x7f) return codePointToString(cp)
    return null
}

/** The control byte for Ctrl+[letter], e.g. ctrl('c') == ETX (0x03). */
fun ctrl(letter: Char): String = ((letter.lowercaseChar().code) and 0x1f).toChar().toString()

/** Codepoint → String, multiplatform (surrogate pair for supplementary planes). */
fun codePointToString(cp: Int): String =
    if (cp <= 0xFFFF) cp.toChar().toString()
    else {
        val c = cp - 0x10000
        charArrayOf((0xD800 + (c shr 10)).toChar(), (0xDC00 + (c and 0x3FF)).toChar()).concatToString()
    }
