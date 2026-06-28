package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.model.TerminalLine
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun TerminalView(state: AzulaState) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.termLines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(18.dp)) {
        state.termLines.forEach { line -> TermLineRow(line) }
        PromptRow(cursor = true)
    }
}

@Composable
private fun TermLineRow(line: TerminalLine) {
    when (line.kind) {
        TerminalLine.Kind.CMD -> Row {
            Prompt()
            Text(line.text, color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
        }
        TerminalLine.Kind.OUT -> {
            val color = when (line.tint) {
                TerminalLine.Tint.GREEN -> AzulaColors.online
                TerminalLine.Tint.ERROR -> AzulaColors.error
                TerminalLine.Tint.NORMAL -> AzulaColors.textDim2
            }
            Text(line.text, color = color, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
private fun Prompt() {
    Text("zuko@remote ", color = AzulaColors.termPromptUser, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
    Text("~/azula", color = AzulaColors.pink, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
    Text(" $ ", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
}

@Composable
private fun PromptRow(cursor: Boolean) {
    Row {
        Prompt()
        if (cursor) {
            val t = rememberInfiniteTransition(label = "cursor")
            val a by t.animateFloat(
                initialValue = 1f, targetValue = 0f,
                animationSpec = infiniteRepeatable(keyframes {
                    durationMillis = 1050
                    1f at 0
                    1f at 520
                    0f at 525
                    0f at 1050
                }, RepeatMode.Restart),
                label = "blink",
            )
            Text("█", color = AzulaColors.pink.copy(alpha = a), fontFamily = Fonts.mono, fontSize = 12.5.sp, lineHeight = 21.sp)
        }
    }
}
