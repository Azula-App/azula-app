package dev.azula.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

/**
 * Renders the markdown subset used in chat: headings, bold/italic/inline-code,
 * code blocks, lists, blockquotes, tables, rules and (styled) links.
 */
@Composable
fun MarkdownText(source: String, color: Color, mine: Boolean = false) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    // markdown has no dependency on a2ui (module.yaml: "depends only on theme"),
    // so these read the same AzulaColors constants A2uiTokens itself wraps
    // (pinkLight == A2uiTokens.primaryLight, inputBg == A2uiTokens.inputBg, …)
    // rather than importing the token object directly.
    val strong = if (mine) Color(0xFFFFFFFF) else AzulaColors.textBright
    val codeColor = if (mine) Color(0xFFFFD9EE) else AzulaColors.pinkLight
    val codeBg = if (mine) Color(0x40000000) else AzulaColors.inputBg
    val linkColor = if (mine) Color(0xFFFFFFFF) else AzulaColors.pinkLight

    Column {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Box(Modifier.width(1.dp).padding(top = 6.dp))
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, strong, codeColor, codeBg, linkColor),
                    color = strong,
                    fontFamily = Fonts.ui,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 14.sp },
                    modifier = Modifier.padding(top = if (idx > 0) 8.dp else 0.dp, bottom = 4.dp),
                )

                is MdBlock.Paragraph -> Text(
                    inline(block.text, strong, codeColor, codeBg, linkColor),
                    color = color, fontFamily = Fonts.ui, fontSize = 13.5.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 3.dp),
                )

                is MdBlock.BulletList -> Column(Modifier.padding(vertical = 3.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•  ", color = AzulaColors.pink, fontFamily = Fonts.ui, fontSize = 13.5.sp)
                            Text(inline(item, strong, codeColor, codeBg, linkColor), color = color, fontFamily = Fonts.ui, fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                    }
                }

                is MdBlock.OrderedList -> Column(Modifier.padding(vertical = 3.dp)) {
                    block.items.forEachIndexed { n, item ->
                        Row {
                            Text("${block.start + n}.  ", color = AzulaColors.pink, fontFamily = Fonts.ui, fontSize = 13.5.sp)
                            Text(inline(item, strong, codeColor, codeBg, linkColor), color = color, fontFamily = Fonts.ui, fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                    }
                }

                is MdBlock.CodeBlock -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(AzulaColors.codeBlock)
                        .border(1.dp, AzulaColors.border3, RoundedCornerShape(9.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(block.code, color = AzulaColors.text2, fontFamily = Fonts.mono, fontSize = 12.sp, lineHeight = 19.sp)
                }

                is MdBlock.BlockQuote -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AzulaColors.pink.copy(alpha = 0.08f)),
                ) {
                    Box(Modifier.width(2.dp).background(AzulaColors.pink))
                    Text(
                        inline(block.text, strong, codeColor, codeBg, linkColor),
                        color = Color(0xFFB8B8C4), fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                is MdBlock.Table -> Column(Modifier.padding(vertical = 5.dp)) {
                    TableRow(block.headers, header = true, strong, codeColor, codeBg, linkColor)
                    block.rows.forEach { TableRow(it, header = false, strong, codeColor, codeBg, linkColor) }
                }

                MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(AzulaColors.border3).width(1.dp)) {
                    Box(Modifier.fillMaxWidth().background(AzulaColors.border3).padding(top = 0.5.dp))
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean, strong: Color, codeColor: Color, codeBg: Color, linkColor: Color) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Box(
                Modifier.weight(1f)
                    .border(1.dp, AzulaColors.border4)
                    .background(if (header) AzulaColors.inputBg else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    inline(cell, strong, codeColor, codeBg, linkColor),
                    color = if (header) Color(0xFFF0E0EA) else AzulaColors.text2,
                    fontFamily = Fonts.ui, fontSize = 12.5.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Inline markdown → styled text: **bold**, *italic*, `code`, [text](url). */
private fun inline(s: String, strong: Color, codeColor: Color, codeBg: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString { appendInline(s, strong, codeColor, codeBg, linkColor) }

private fun AnnotatedString.Builder.appendInline(s: String, strong: Color, codeColor: Color, codeBg: Color, linkColor: Color) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = Fonts.mono, background = codeBg, color = codeColor)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '*' && i + 1 < s.length && s[i + 1] == '*' -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) {
                        appendInline(s.substring(i + 2, end), strong, codeColor, codeBg, linkColor)
                    }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(s.substring(i + 1, end), strong, codeColor, codeBg, linkColor)
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            appendInline(s.substring(i + 1, close), strong, codeColor, codeBg, linkColor)
                        }
                        i = urlEnd + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
