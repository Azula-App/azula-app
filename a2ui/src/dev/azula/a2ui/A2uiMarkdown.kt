package dev.azula.a2ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.Fonts

/**
 * A deliberately small Markdown subset for the A2UI `Text` component — the catalog
 * renders `text` as Markdown and "falls back to raw text if the parser is
 * unavailable". Supported: `### ` headings, `- `/`* ` bullet lists, blank-line
 * paragraph breaks, and inline `**bold**`, `*italic*`, `` `code` ``. Anything else
 * is shown verbatim.
 */

/** True if [s] contains any markdown token worth parsing (else render as plain text). */
internal fun hasMarkdown(s: String): Boolean =
    s.contains("**") || s.contains('`') ||
        Regex("""(^|\n)\s*(#{1,6}\s|[-*]\s)""").containsMatchIn(s) ||
        Regex("""(?<![*\w])\*(?!\s)[^*\n]+\*""").containsMatchIn(s)

@Composable
internal fun MarkdownText(
    text: String,
    baseSize: TextUnit,
    baseWeight: FontWeight,
    baseColor: androidx.compose.ui.graphics.Color,
) {
    val lines = text.split('\n')
    Column {
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> Text("", fontSize = (baseSize.value * 0.4f).sp)
                trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                    val body = trimmed.substringAfter(' ')
                    Text(
                        text = inline(body),
                        color = A2uiTokens.contentBright,
                        fontFamily = Fonts.ui,
                        fontSize = (baseSize.value + 2f).sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = if (i == 0) Modifier else Modifier.padding(top = 6.dp),
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            "•  ",
                            color = A2uiTokens.primary,
                            fontFamily = Fonts.ui,
                            fontSize = baseSize,
                        )
                        Text(
                            text = inline(trimmed.substring(2)),
                            color = baseColor,
                            fontFamily = Fonts.ui,
                            fontSize = baseSize,
                            fontWeight = baseWeight,
                        )
                    }
                }
                else -> Text(
                    text = inline(line),
                    color = baseColor,
                    fontFamily = Fonts.ui,
                    fontSize = baseSize,
                    fontWeight = baseWeight,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/** Parse inline `**bold**`, `*italic*`, `` `code` `` into an [AnnotatedString]. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = A2uiTokens.contentBright)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = Fonts.mono, color = A2uiTokens.primaryLight)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
