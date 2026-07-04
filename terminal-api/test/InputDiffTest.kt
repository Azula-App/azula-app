import dev.azula.terminal.InputSentinel
import dev.azula.terminal.TermSeq
import dev.azula.terminal.diffToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class InputDiffTest {

    @Test
    fun noOpWhenTextUnchanged() {
        val d = diffToBytes("${InputSentinel}hello", "${InputSentinel}hello")
        assertEquals("", d.backspaces)
        assertEquals("", d.suffix)
        assertEquals(true, d.isEmpty)
    }

    @Test
    fun sentinelEdgeBothBare() {
        val d = diffToBytes(InputSentinel, InputSentinel)
        assertEquals("", d.backspaces)
        assertEquals("", d.suffix)
    }

    @Test
    fun swipedWordIsPureAppend() {
        val d = diffToBytes(InputSentinel, "${InputSentinel}hello")
        assertEquals("", d.backspaces)
        assertEquals("hello", d.suffix)
    }

    @Test
    fun autocorrectAppendsSuffixGiToGit() {
        // Gboard extends "gi" to "git" without touching the shared prefix.
        val d = diffToBytes("${InputSentinel}gi", "${InputSentinel}git")
        assertEquals("", d.backspaces)
        assertEquals("t", d.suffix)
    }

    @Test
    fun autocorrectRewritesMiddleTehToThe() {
        // Autocorrect fires on the trigger space: "teh " -> "the ".
        val d = diffToBytes("${InputSentinel}teh ", "${InputSentinel}the ")
        assertEquals(TermSeq.BACKSPACE.repeat(3), d.backspaces)
        assertEquals("he ", d.suffix)
    }

    @Test
    fun midStringEditReplacesOneChar() {
        val d = diffToBytes("${InputSentinel}abcdef", "${InputSentinel}abXdef")
        assertEquals(TermSeq.BACKSPACE.repeat(4), d.backspaces)
        assertEquals("Xdef", d.suffix)
    }

    @Test
    fun shrinkIsPureBackspaces() {
        val d = diffToBytes("${InputSentinel}hello", "${InputSentinel}hel")
        assertEquals(TermSeq.BACKSPACE.repeat(2), d.backspaces)
        assertEquals("", d.suffix)
    }
}
