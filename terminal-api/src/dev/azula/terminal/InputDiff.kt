package dev.azula.terminal

/**
 * The mobile soft-keyboard capture field's baseline character: a permanent,
 * invisible one-char prefix kept at the front of the field so a soft
 * keyboard's Backspace-on-an-already-empty-field (which many IMEs emit as
 * literally no [android.view.inputmethod.InputConnection] call at all) stays
 * detectable -- the field never truly empties, so a value shorter than this
 * sentinel is unambiguous. A real NBSP so it can never collide with a
 * printable ASCII space the user legitimately types.
 */
const val InputSentinel: String = " "

/**
 * The result of diffing the mobile input field's previous and current text
 * against a stable, never-reset buffer: a homogeneous run of backspaces
 * (undoing whatever followed the common prefix) followed by the newly
 * typed/inserted suffix. Sent to the PTY as two separate calls -- one
 * all-backspace, one all-print -- so [PredictionEngine] only ever needs to
 * predict homogeneous strings, never a mix.
 */
data class InputDiff(val backspaces: String, val suffix: String) {
    val isEmpty: Boolean get() = backspaces.isEmpty() && suffix.isEmpty()
}

/**
 * Pure diff between a text field's previous ([old]) and current ([new])
 * content: the longest common prefix the two strings share, then (a) one
 * [TermSeq.BACKSPACE] per character of [old] beyond that prefix, and (b)
 * whatever's left of [new] beyond it. Both autocorrect fix-ups (which rewrite
 * a run in the middle of the string, e.g. "teh " -> "the ") and plain
 * appends/deletes/swipe-inserts reduce to this one shape.
 */
fun diffToBytes(old: String, new: String): InputDiff {
    var p = 0
    val minLen = minOf(old.length, new.length)
    while (p < minLen && old[p] == new[p]) p++
    val backspaceCount = old.length - p
    return InputDiff(TermSeq.BACKSPACE.repeat(backspaceCount), new.substring(p))
}
