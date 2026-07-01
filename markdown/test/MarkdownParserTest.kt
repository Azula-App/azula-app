import dev.azula.markdown.MarkdownParser
import dev.azula.markdown.MdBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownParserTest {
    @Test
    fun parsesHeadingsParagraphsAndCode() {
        val blocks = MarkdownParser.parse("# Title\n\nsome **bold** text\n\n```bash\niroh connect\n```")
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue(blocks.any { it is MdBlock.Paragraph })
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("bash", code.lang)
        assertEquals("iroh connect", code.code)
    }

    @Test
    fun parsesListsTablesAndQuotes() {
        val md = "- one\n- two\n\n> a quote\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        val blocks = MarkdownParser.parse(md)
        assertEquals(listOf("one", "two"), (blocks.filterIsInstance<MdBlock.BulletList>().single()).items)
        assertTrue(blocks.any { it is MdBlock.BlockQuote })
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }
}
