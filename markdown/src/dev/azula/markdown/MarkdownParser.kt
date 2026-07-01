package dev.azula.markdown

/** Block-level model for the markdown subset azula renders. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class OrderedList(val start: Int, val items: List<String>) : MdBlock()
    data class CodeBlock(val lang: String, val code: String) : MdBlock()
    data class BlockQuote(val text: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    object Rule : MdBlock()
}

/** A tiny, dependency-free line-based markdown parser for chat content. */
object MarkdownParser {
    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++

                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        body.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < lines.size) i++ // closing fence
                    blocks += MdBlock.CodeBlock(lang, body.toString().trimEnd('\n'))
                }

                isRule(trimmed) -> { blocks += MdBlock.Rule; i++ }

                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                    i++
                }

                trimmed.startsWith(">") -> {
                    val quote = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote.append(lines[i].trim().removePrefix(">").trim()).append('\n')
                        i++
                    }
                    blocks += MdBlock.BlockQuote(quote.toString().trim())
                }

                isTableHeader(lines, i) -> {
                    val headers = splitRow(lines[i])
                    i += 2 // header + separator
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains("|") && lines[i].trim().isNotEmpty()) {
                        rows += splitRow(lines[i]); i++
                    }
                    blocks += MdBlock.Table(headers, rows)
                }

                isBullet(trimmed) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && isBullet(lines[i].trim())) {
                        items += lines[i].trim().drop(1).trim(); i++
                    }
                    blocks += MdBlock.BulletList(items)
                }

                isOrdered(trimmed) -> {
                    val start = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                    val items = mutableListOf<String>()
                    while (i < lines.size && isOrdered(lines[i].trim())) {
                        items += lines[i].trim().substringAfter('.').trim(); i++
                    }
                    blocks += MdBlock.OrderedList(start, items)
                }

                else -> {
                    val para = StringBuilder()
                    while (i < lines.size && lines[i].trim().isNotEmpty() && !isBlockStart(lines, i)) {
                        if (para.isNotEmpty()) para.append('\n')
                        para.append(lines[i].trim())
                        i++
                    }
                    if (para.isNotEmpty()) blocks += MdBlock.Paragraph(para.toString())
                }
            }
        }
        return blocks
    }

    private fun isRule(t: String) = t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' })
    private fun isBullet(t: String) = (t.startsWith("- ") || t.startsWith("* ")) && t.length > 2
    private fun isOrdered(t: String): Boolean {
        val n = t.takeWhile { it.isDigit() }
        return n.isNotEmpty() && t.length > n.length && t[n.length] == '.'
    }

    private fun isTableHeader(lines: List<String>, i: Int): Boolean {
        if (!lines[i].contains("|")) return false
        val next = lines.getOrNull(i + 1)?.trim() ?: return false
        return next.contains("|") && next.replace("|", "").replace(":", "").trim().all { it == '-' || it == ' ' } &&
            next.contains("-")
    }

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }

    private fun isBlockStart(lines: List<String>, i: Int): Boolean {
        val t = lines[i].trim()
        return t.startsWith("```") || t.startsWith("#") || t.startsWith(">") ||
            isBullet(t) || isOrdered(t) || isRule(t) || isTableHeader(lines, i)
    }
}
