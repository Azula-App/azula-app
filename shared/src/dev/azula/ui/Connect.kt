package dev.azula.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.link.AzulaLinks
import dev.azula.link.Qr
import dev.azula.model.ConnectStage
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun ConnectScreen(state: AzulaState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
        when (state.connectStage) {
            ConnectStage.DIALING, ConnectStage.CONNECTED -> HolepunchProgress(state)
            else -> ConnectIdle(state)
        }
    }
}

@Composable
private fun ConnectIdle(state: AzulaState) {
    val clipboard = LocalClipboardManager.current
    var showShare by remember { mutableStateOf(false) }
    var pendingTicket by remember { mutableStateOf("") }
    // Connecting opens the share-sheet (pre-filled from the default persona) so
    // the user can choose what to present; if they have no personas, connect
    // straight away with nothing shared.
    fun beginConnect(ticket: String) {
        if (ticket.isBlank()) return
        if (state.personas.isEmpty()) state.connectPeer(ticket)
        else { pendingTicket = ticket; showShare = true }
    }
    if (showShare) {
        ShareSheet(
            state = state,
            title = "Share a profile?",
            subtitle = "Choose which persona — and which details — to show this peer.",
            confirmLabel = "Share & connect",
            dismissLabel = "Connect without sharing",
            onResult = { frame -> showShare = false; state.connectPeer(pendingTicket, frame) },
            onCancel = { showShare = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("YOUR CODE", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.shortCode, color = AzulaColors.pink, fontFamily = Fonts.mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(AzulaColors.inputBg)
                    .border(1.dp, AzulaColors.border4, RoundedCornerShape(8.dp))
                    .clickable { clipboard.setText(AnnotatedString(state.myCode)); state.markCopied() }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    if (state.copied) "✓ copied" else "copy",
                    color = if (state.copied) AzulaColors.onlineBright else AzulaColors.textDim,
                    fontFamily = Fonts.mono, fontSize = 11.sp,
                )
            }
        }
        Text(
            "anyone with this code can open a direct, end-to-end-encrypted chat with you. no account, no server.",
            color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.5.sp, lineHeight = 18.sp,
        )
        if (state.myCode.isNotEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(AzulaLinks.webLink(state.myCode)) }
        }
        if (state.connectStage == ConnectStage.FAILED) {
            Text("couldn't reach that peer — check the code and try again.", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(AzulaColors.border3))
            Text("or join a peer", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
            Box(Modifier.weight(1f).height(1.dp).background(AzulaColors.border3))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AzulaColors.inputBg)
                    .border(1.dp, AzulaColors.border4, RoundedCornerShape(10.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                BasicTextField(
                    value = state.connectInput,
                    onValueChange = { state.connectInput = it },
                    singleLine = true,
                    textStyle = TextStyle(color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp),
                    cursorBrush = SolidColor(AzulaColors.pink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { beginConnect(state.connectInput) }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (state.connectInput.isEmpty()) Text("paste a code…", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                        inner()
                    },
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(PinkButtonBrush)
                    .clickable { beginConnect(state.connectInput) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text("connect", color = Color.White, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HolepunchProgress(state: AzulaState) {
    val connected = state.connectStage == ConnectStage.CONNECTED
    val step = state.connectStep
    // Who we're dialing: the peer's code from the pasted input, plus any identity
    // (name / avatar / description) once the connection resolves it. This is the one
    // place the mnemonic "three words" belongs — the chat view shows status instead.
    val peerId = state.peerIdForInput(state.connectInput)
    val peerCode = state.peerCodeForInput(state.connectInput)
    val peerConv = peerId?.let { state.conversationOrNull(it) }
    val peerCs = peerId?.let { state.convOrNull(it) }
    val peerName = peerConv?.name?.takeIf { peerCode == null || it != peerCode }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            if (connected) "CONNECTED" else "HOLEPUNCHING",
            color = if (connected) AzulaColors.onlineBright else AzulaColors.pink,
            fontFamily = Fonts.pixel, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
        // ── who you're connecting to ──
        if (peerCode != null || peerName != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CONNECTING TO", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 10.sp)
                if (peerConv != null) AvatarBox(peerCs?.peerAvatarBitmap, peerConv.glyph, peerConv.kind, size = 52.dp)
                if (peerName != null) Text(peerName, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                peerCode?.let { Text(it, color = AzulaColors.pink, fontFamily = Fonts.mono, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                peerCs?.peerDescription?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            if (connected) {
                Box(Modifier.size(62.dp).clip(CircleShape).border(3.dp, AzulaColors.onlineBright, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", color = AzulaColors.onlineBright, fontSize = 26.sp)
                }
            } else {
                CircularProgressIndicator(color = AzulaColors.pink, strokeWidth = 3.dp, modifier = Modifier.size(62.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressStep(if (peerCode != null) "found peer · $peerCode" else "found peer", step >= 1 || connected)
            ProgressStep("punching NAT", step >= 2 || connected)
            ProgressStep(if (connected) "direct path established" else "opening direct path…", connected)
        }
        if (connected) {
            Text("opening chat…", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ProgressStep(label: String, done: Boolean) {
    Text(
        (if (done) "✓ " else "› ") + label,
        color = if (done) AzulaColors.online else AzulaColors.warn,
        fontFamily = Fonts.mono, fontSize = 12.sp,
    )
}

/**
 * A real, scannable QR of the connect [data] link — hand-rolled encoder (see [Qr]).
 * Dark modules on a light card (high luminance contrast is what camera scanners
 * need; the brand pink-on-dark would not reliably decode).
 */
@Composable
private fun QrCode(data: String) {
    val modules = remember(data) { Qr.encode(data, Qr.Ecc.MEDIUM) }
    val n = modules.size
    val dark = Color(0xFF070709) // brand near-black
    Box(
        Modifier.size(168.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(1.dp, AzulaColors.border4, RoundedCornerShape(12.dp)),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            // Reserve a quiet zone (~4 modules of white) so the finder patterns read cleanly.
            val quiet = 4
            val cell = size.width / (n + quiet * 2)
            val origin = cell * quiet
            for (y in 0 until n) for (x in 0 until n) {
                if (modules[y][x]) {
                    drawRect(
                        color = dark,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f), // slight overdraw kills seams
                    )
                }
            }
        }
    }
}
