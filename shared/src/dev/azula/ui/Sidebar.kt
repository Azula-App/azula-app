package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.getWorld
import dev.azula.model.Conversation
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun Sidebar(state: AzulaState, modifier: Modifier = Modifier) {
    Column(modifier.background(AzulaColors.bgSidebar).padding(horizontal = 13.dp, vertical = 16.dp)) {
        Brand()
        Spacer(Modifier.height(14.dp))
        ConnectRow(active = state.desktopActive == "connect") { state.openConnectDesktop() }
        Spacer(Modifier.height(6.dp))
        SectionLabel("CONVERSATIONS")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet — connect a peer to begin.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
            items(state.conversations, key = { it.id }) { conv ->
                ConversationRow(
                    conv = conv,
                    active = state.desktopActive == conv.id,
                    online = state.convOrNull(conv.id)?.online == true,
                    avatar = state.convOrNull(conv.id)?.peerAvatarBitmap,
                    onClick = { state.selectDesktop(conv.id) },
                    onToggleEnabled = { state.setConversationEnabled(conv.id, !conv.enabled) },
                    onDelete = { state.deleteConversation(conv.id) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsRow(active = state.desktopActive == "settings") { state.openSettingsDesktop() }
        Spacer(Modifier.height(10.dp))
        YourCodeBox(state)
    }
}

@Composable
fun SettingsRow(active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(if (active) Color(0x14FFFFFF) else Color.Transparent)
            .border(1.dp, if (active) AzulaColors.border4 else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("⚙", color = AzulaColors.textDim, fontSize = 14.sp)
        Text("profiles & settings", color = AzulaColors.text2, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
fun Brand() {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("azula", color = AzulaColors.pink, fontFamily = Fonts.ui, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text("p2p over iroh · ${getWorld()}", color = AzulaColors.textFaint2, fontFamily = Fonts.mono, fontSize = 10.sp)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = AzulaColors.textFaint2, fontFamily = Fonts.mono, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
}

@Composable
fun ConnectRow(active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(if (active) Color(0x1FFF2D9B) else Color.Transparent)
            .border(1.dp, if (active) Color(0x99FF2D9B) else Color(0x66FF2D9B), RoundedCornerShape(11.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("+", color = AzulaColors.pinkLight, fontSize = 15.sp)
        Text("connect a peer", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
fun ConversationRow(
    conv: Conversation,
    active: Boolean,
    online: Boolean,
    avatar: ImageBitmap? = null,
    onClick: () -> Unit,
    onToggleEnabled: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0x1AFF2D9B) else Color.Transparent)
            .border(1.dp, if (active) Color(0x4DFF2D9B) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 10.dp)
            .alpha(if (conv.enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AvatarBox(avatar, conv.glyph, conv.kind)
        Column(Modifier.weight(1f)) {
            Text(
                conv.name,
                color = if (active) Color(0xFFFCE8F3) else AzulaColors.text1,
                fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (conv.enabled) conv.preview else "disabled",
                color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        StatusDot(if (online && conv.enabled) AzulaColors.online else dotColorFor(conv.kind))
        if (onToggleEnabled != null || onDelete != null) {
            ConvOverflowMenu(enabled = conv.enabled, onToggleEnabled = onToggleEnabled, onDelete = onDelete)
        }
    }
}

@Composable
fun YourCodeBox(state: AzulaState) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AzulaColors.bg2)
            .border(1.dp, AzulaColors.border2, RoundedCornerShape(12.dp)).padding(11.dp),
    ) {
        Text("YOUR CODE", color = AzulaColors.textFaint2, fontFamily = Fonts.mono, fontSize = 10.sp)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.shortCode, color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, AzulaColors.border4, RoundedCornerShape(6.dp))
                    .clickable { clipboard.setText(AnnotatedString(state.myCode)); state.markCopied() }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(if (state.copied) "copied ✓" else "copy", color = if (state.copied) AzulaColors.onlineBright else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 10.sp)
            }
        }
    }
}
