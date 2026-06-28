package dev.azula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.markdown.MarkdownText
import dev.azula.model.Conversation
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun ChatPane(state: AzulaState, convId: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val conv = state.conversation(convId)
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AzulaColors.bg2, AzulaColors.bg1)))) {
        ChatHeader(state, conv, onBack)
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
            if (convId == "term") TerminalView(state) else MessageThread(state, convId)
        }
        val cs = state.conv(convId)
        val termLive = convId == "term" && cs.online
        ChatInput(
            value = cs.draft,
            onValueChange = { state.updateDraft(convId, it) },
            onSend = { state.send(convId) },
            placeholder = when {
                termLive -> "type a command…"
                convId == "term" -> "zuko@remote ~/azula $"
                else -> "message ${conv.name}"
            },
            terminal = convId == "term",
            onInterrupt = if (termLive) { { state.terminalInterrupt() } } else null,
        )
    }
}

@Composable
fun ChatHeader(state: AzulaState, conv: Conversation, onBack: (() -> Unit)? = null) {
    val cs = state.conv(conv.id)
    val online = cs.online
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 3.dp)) {
                Text("‹", color = AzulaColors.textDim, fontSize = 22.sp)
            }
        }
        GlyphBox(glyphStyleFor(conv.kind), conv.glyph)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(conv.name, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (online) AzulaColors.online else dotColorFor(conv.kind))
                Spacer(Modifier.width(7.dp))
                val rtt = state.rttMs
                val sub = if (online && rtt != null) "direct · ${rtt}ms · e2e" else conv.sub
                Text(sub, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
}

@Composable
fun MessageThread(state: AzulaState, convId: String) {
    val cs = state.conv(convId)
    val listState = rememberLazyListState()
    val thinking = convId == "azula" && state.thinking
    val count = cs.messages.size + if (thinking) 1 else 0
    LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(18.dp)) }
        items(cs.messages, key = { it.id }) { m ->
            if (m.widget != null) WidgetHost(state, m) else MessageBubble(m)
        }
        if (thinking) item { ThinkingIndicator() }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun MessageBubble(m: Message) {
    val me = m.role == Role.ME
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (me) Arrangement.End else Arrangement.Start,
    ) {
        val shape = if (me) RoundedCornerShape(15.dp, 15.dp, 5.dp, 15.dp) else RoundedCornerShape(15.dp, 15.dp, 15.dp, 5.dp)
        val bg: Modifier = if (me) {
            Modifier.background(Brush.verticalGradient(listOf(Color(0x38FF2D9B), Color(0x1AFF2D9B))))
                .border(1.dp, Color(0x66FF2D9B), shape)
        } else {
            Modifier.background(AzulaColors.msgOther).border(1.dp, AzulaColors.border3, shape)
        }
        Column(
            Modifier.widthIn(max = 460.dp).clip(shape).then(bg).padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            if (m.role == Role.AZULA) {
                Text("✦ azula", color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            MarkdownText(m.text ?: "", color = if (me) Color(0xFFFBE6F1) else AzulaColors.text2, mine = me)
        }
    }
}

@Composable
fun ThinkingIndicator() {
    val t = rememberInfiniteTransition(label = "think")
    Row(
        Modifier.clip(RoundedCornerShape(15.dp, 15.dp, 15.dp, 5.dp)).background(AzulaColors.msgOther)
            .border(1.dp, AzulaColors.border3, RoundedCornerShape(15.dp, 15.dp, 15.dp, 5.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 150)),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).clip(CircleShape).background(AzulaColors.pink.copy(alpha = a)))
        }
    }
}

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    terminal: Boolean,
    onInterrupt: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    fun sendAndDismiss() { onSend(); focusManager.clearFocus() }
    Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // ^C interrupt button — shown only when a live term stream is active.
        if (onInterrupt != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, AzulaColors.border5, RoundedCornerShape(7.dp))
                    .clickable { onInterrupt() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("^C", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
        }
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                .background(if (terminal) AzulaColors.codeBlock else AzulaColors.inputBg)
                .border(1.dp, if (terminal) Color(0x4DFF2D9B) else AzulaColors.border4, RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = if (terminal) AzulaColors.termGreen else AzulaColors.text1,
                    fontFamily = if (terminal) Fonts.mono else Fonts.ui,
                    fontSize = 13.5.sp,
                ),
                cursorBrush = SolidColor(AzulaColors.pink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendAndDismiss() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, color = AzulaColors.textFaint, fontFamily = if (terminal) Fonts.mono else Fonts.ui, fontSize = 13.5.sp)
                    }
                    inner()
                },
            )
        }
        SendButton(::sendAndDismiss)
    }
}

@Composable
fun SendButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(PinkButtonBrush).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("↑", color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}
