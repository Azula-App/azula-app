import dev.azula.terminal.TermScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TermScreenTest {

    // ---- plain newlines ----

    @Test
    fun plainNewlineCommitsRows() {
        val ts = TermScreen()
        ts.feed("hello\nworld\n")
        assertEquals(2, ts.rows.size)
        assertEquals("hello", ts.rowsText[0])
        assertEquals("world", ts.rowsText[1])
        assertEquals("", ts.currentText)
    }

    @Test
    fun chunkSplittingAcrossFeeds() {
        // Feeding "foo" then "bar\n" should produce a single committed row "foobar".
        val ts = TermScreen()
        ts.feed("foo")
        ts.feed("bar\n")
        assertEquals(1, ts.rows.size)
        assertEquals("foobar", ts.rowsText[0])
    }

    // ---- carriage-return overwrite ----

    @Test
    fun carriageReturnOverwrite() {
        // "abc\rX" → col resets to 0, X overwrites 'a' → "Xbc"
        val ts = TermScreen()
        ts.feed("abc\rX")
        assertEquals("Xbc", ts.currentText)
    }

    @Test
    fun carriageReturnThenNewline() {
        val ts = TermScreen()
        // "loading..." is 10 chars; "100%" (4 chars) overwrites from col 0,
        // leaving the tail "ing..." still in the buffer → "100%ing..."
        ts.feed("loading...\r100%\n")
        assertEquals(1, ts.rows.size)
        assertEquals("100%ing...", ts.rowsText[0])
    }

    // ---- ANSI color strip ----

    @Test
    fun ansiColorEscapesAreStripped() {
        // ESC[31m ... ESC[0m wraps "red"; only "red" should remain.
        val ts = TermScreen()
        ts.feed("[31mred[0m\n")
        assertEquals(1, ts.rows.size)
        assertEquals("red", ts.rowsText[0])
    }

    @Test
    fun bold256ColorsStripped() {
        val ts = TermScreen()
        ts.feed("[1;32mgreen bold[0m\n")
        assertEquals("green bold", ts.rowsText[0])
    }

    @Test
    fun sgrSetsSpanColorAndBold() {
        val ts = TermScreen()
        ts.feed("[31mred[0m")
        assertEquals("red", ts.currentText)
        val span = ts.current.first { it.text == "red" }
        assertEquals(0xFFE06C75, span.color)  // ANSI 31 (red) in the palette

        val ts2 = TermScreen()
        ts2.feed("[1;38;5;208mbold-orange")
        val s2 = ts2.current.first { it.text.contains("bold-orange") }
        assertTrue(s2.bold, "expected bold")
        assertTrue(s2.color != null, "expected a 256-color fg")
    }

    // ---- CSI K erase to EOL ----

    @Test
    fun csiKErasesToEol() {
        val ts = TermScreen()
        // Write "hello", move cursor back 3 via \r + re-type "he", then CSI K
        ts.feed("hello")          // sb="hello", col=5
        ts.feed("\rhe[K")   // col→0, write h→col1, write e→col2, then CSI K truncates to col=2
        assertEquals("he", ts.currentText)
    }

    // ---- ESC[2J / ESC c clear ----

    @Test
    fun csi2JClearsEverything() {
        val ts = TermScreen()
        ts.feed("line1\nline2\n")
        ts.feed("[2J")
        assertTrue(ts.rows.isEmpty())
        assertEquals("", ts.currentText)
    }

    @Test
    fun escCFullReset() {
        val ts = TermScreen()
        ts.feed("line1\npartial")
        ts.feed("c")
        assertTrue(ts.rows.isEmpty())
        assertEquals("", ts.currentText)
    }

    @Test
    fun csiJNakedParamClears() {
        // ESC[J (no param / "0") → also clears
        val ts = TermScreen()
        ts.feed("abc\n")
        ts.feed("[J")
        assertTrue(ts.rows.isEmpty())
    }

    // ---- tab expansion ----

    @Test
    fun tabExpandsToNextMultipleOf8() {
        val ts = TermScreen()
        ts.feed("ab\t!")  // col=2 → next tab stop is 8 → 6 spaces → col=8, then '!'
        assertEquals("ab      !", ts.currentText)
    }

    @Test
    fun tabAtColumnZero() {
        val ts = TermScreen()
        ts.feed("\t!")
        // col=0 → next tab stop is 8 → 8 spaces → col=8, then '!'
        assertEquals("        !", ts.currentText)
    }

    // ---- OSC sequences ignored ----

    @Test
    fun oscTitleSequenceIgnored() {
        val ts = TermScreen()
        // OSC 0 ; "title" BEL — sets terminal title, should be silently ignored
        ts.feed("]0;my titlehello\n")
        assertEquals("hello", ts.rowsText[0])
    }

    // ---- cap at 5000 lines ----

    @Test
    fun rowsCappedAt5000() {
        val ts = TermScreen()
        repeat(5100) { i -> ts.feed("line$i\n") }
        // After cap enforcement, rows should not exceed 5000.
        assertTrue(ts.rows.size <= 5000, "rows.size=${ts.rows.size} exceeded 5000")
    }
}
