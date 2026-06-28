package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.model.DicePhase
import dev.azula.model.InlineWidget
import dev.azula.model.Message
import dev.azula.model.WidgetState
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

private val DICE = listOf("·", "⚀", "⚁", "⚂", "⚃", "⚄", "⚅")

@Composable
fun WidgetHost(state: AzulaState, message: Message) {
    val w = state.widgets[message.id] ?: WidgetState()
    when (val widget = message.widget) {
        is InlineWidget.Decide -> DecideWidget(widget, w) { state.pickDecide(message.id, it) }
        is InlineWidget.Poll -> PollWidget(widget, w) { state.vote(message.id, it) }
        is InlineWidget.Dice -> DiceWidget(w) { state.rollDice(message.id) }
        null -> Unit
    }
}

@Composable
private fun WidgetCard(borderColor: Color, label: String, labelColor: Color, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 440.dp)
                .background(Color(0xFF120C1A))
                .border(2.dp, borderColor)
                .padding(14.dp),
        ) {
            Text(label, color = labelColor, fontFamily = Fonts.pixel, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DecideWidget(widget: InlineWidget.Decide, w: WidgetState, onPick: (String) -> Unit) {
    WidgetCard(AzulaColors.pink, "AZULA · DECIDE", AzulaColors.pinkLight) {
        Text(widget.q, color = Color(0xFFF0E6EE), fontFamily = Fonts.vt323, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            widget.opts.forEach { opt ->
                val chosen = w.chosen == opt
                Box(
                    Modifier.border(2.dp, AzulaColors.pink)
                        .background(if (chosen) AzulaColors.pink else Color.Transparent)
                        .clickable { onPick(opt) }
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                ) {
                    Text(opt, color = if (chosen) Color(0xFF0D0815) else AzulaColors.pinkLight, fontFamily = Fonts.pixel, fontSize = 9.sp)
                }
            }
        }
        if (w.chosen != null) {
            Text("✓ locked in: ${w.chosen}", color = AzulaColors.onlineBright, fontFamily = Fonts.vt323, fontSize = 17.sp, modifier = Modifier.padding(top = 11.dp))
        }
    }
}

@Composable
private fun PollWidget(widget: InlineWidget.Poll, w: WidgetState, onVote: (Int) -> Unit) {
    WidgetCard(AzulaColors.pink, "AZULA · POLL", AzulaColors.pinkLight) {
        Text(widget.q, color = Color(0xFFF0E6EE), fontFamily = Fonts.vt323, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp, bottom = 12.dp))
        val tallies = widget.opts.mapIndexed { i, o -> o.label to (o.votes + if (w.voted == i) 1 else 0) }
        val total = tallies.sumOf { it.second }.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            tallies.forEachIndexed { i, (label, votes) ->
                val pct = (votes * 100 / total)
                Box(
                    Modifier.fillMaxWidth().height(30.dp).border(2.dp, Color(0xFF2A1D3A))
                        .background(Color(0xFF0D0815))
                        .clickable { onVote(i) },
                ) {
                    Box(
                        Modifier.fillMaxWidth(pct / 100f).fillMaxHeight()
                            .background(
                                if (w.voted == i) Brush.horizontalGradient(listOf(Color(0x99FF2D9B), Color(0x4DFF2D9B)))
                                else Brush.horizontalGradient(listOf(Color(0x29FF2D9B), Color(0x29FF2D9B))),
                            ),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp).fillMaxHeight(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, color = Color(0xFFE6DCEE), fontFamily = Fonts.vt323, fontSize = 17.sp)
                        Text("$pct%", color = Color(0xFFE6DCEE), fontFamily = Fonts.vt323, fontSize = 17.sp)
                    }
                }
            }
        }
        val voted = if (w.voted != null) "you voted · " else ""
        Text("$voted$total votes", color = Color(0xFF6A5A7A), fontFamily = Fonts.vt323, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DiceWidget(w: WidgetState, onRoll: () -> Unit) {
    WidgetCard(AzulaColors.warn, "AZULA · DICE", AzulaColors.warn) {
        val youFace = if (w.dicePhase == DicePhase.DONE) DICE[w.you] else "?"
        val themFace = if (w.dicePhase == DicePhase.DONE) DICE[w.them] else "?"
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiceFace(youFace, AzulaColors.pink, "you")
            Text("vs", color = Color(0xFF6A5A7A), fontFamily = Fonts.vt323, fontSize = 16.sp)
            DiceFace(themFace, AzulaColors.onlineBright, "zuko")
        }
        if (w.dicePhase == DicePhase.DONE) {
            val res = when {
                w.you == w.them -> "tie — roll again"
                w.you > w.them -> "you win 🏆"
                else -> "zuko wins"
            }
            Text(res, color = AzulaColors.warn, fontFamily = Fonts.vt323, fontSize = 21.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val rolling = w.dicePhase == DicePhase.ROLLING
            Box(
                Modifier.wrapContentWidth().background(if (rolling) Color(0xFF6A5A2A) else AzulaColors.warn)
                    .clickable(enabled = !rolling) { onRoll() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val label = when (w.dicePhase) { DicePhase.ROLLING -> "ROLLING…"; DicePhase.DONE -> "ROLL AGAIN"; else -> "ROLL" }
                Text(label, color = Color(0xFF0D0815), fontFamily = Fonts.pixel, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DiceFace(face: String, color: Color, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(face, color = color, fontFamily = Fonts.vt323, fontSize = 42.sp)
        Spacer(Modifier.height(2.dp))
        Text(name, color = Color(0xFF9A8AAA), fontFamily = Fonts.vt323, fontSize = 13.sp)
    }
}
