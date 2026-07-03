package dev.azula.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.state.AzulaState
import dev.azula.state.MScreen
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun MobileApp(state: AzulaState) {
    // System back (e.g. Android's back button / gesture) navigates within the
    // app's mobile nav instead of exiting it.
    PlatformBackHandler(enabled = state.mScreen != MScreen.HOME) { state.mobileBack() }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AzulaColors.bg2, AzulaColors.bg1)))) {
        // Keep the gradient full-bleed (edge to edge) but inset the content so it
        // clears the status bar / camera cutout / home indicator and the keyboard.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedContent(
                targetState = state.mScreen,
                transitionSpec = {
                    // HOME is the root: pushing a screen slides it in from the
                    // right; popping back slides it out to the right again.
                    val forward = initialState == MScreen.HOME
                    val spec = tween<IntOffset>(280, easing = FastOutSlowInEasing)
                    if (forward) {
                        (slideInHorizontally(spec) { it } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(280)))
                    } else {
                        (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(spec) { it } + fadeOut(tween(280)))
                    }
                },
            ) { screen ->
                when (screen) {
                    MScreen.HOME -> MobileHome(state)
                    MScreen.CONNECT -> MobileConnect(state)
                    MScreen.CHAT -> ChatPane(state, state.mActive, onBack = { state.mobileBack() })
                    MScreen.SETTINGS -> SettingsScreen(state, onBack = { state.mobileBack() })
                    MScreen.INVITES -> InvitesScreen(state, onBack = { state.mobileBack() })
                }
            }
        }
        FullscreenMediaOverlay(state)
    }
}

@Composable
private fun MobileHome(state: AzulaState) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Brand()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("◉ online", color = AzulaColors.online, fontFamily = Fonts.mono, fontSize = 11.sp)
                    Text(
                        if (state.pendingInvites.isEmpty()) "✉" else "✉ ${state.pendingInvites.size}",
                        color = if (state.pendingInvites.isEmpty()) AzulaColors.textDim else AzulaColors.pinkLight,
                        fontFamily = Fonts.mono, fontSize = 14.sp,
                        modifier = Modifier.clickable { state.openInvitesMobile() },
                    )
                    Text(
                        "⚙", color = AzulaColors.textDim, fontSize = 17.sp,
                        modifier = Modifier.clickable { state.openSettingsMobile() },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (state.pendingInvites.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                        .background(Color(0x1FFF2D9B)).border(1.dp, Color(0x66FF2D9B), RoundedCornerShape(11.dp))
                        .clickable { state.openInvitesMobile() }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.pendingInvites.size == 1) "1 connection request" else "${state.pendingInvites.size} connection requests",
                            color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        )
                        Text("review ›", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(9.dp))
            }
            YourCodeBox(state)
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Color(0x33FF2D9B), Color(0x14FF2D9B))))
                    .border(1.dp, Color(0x66FF2D9B), RoundedCornerShape(13.dp))
                    .clickable { state.openConnectMobile() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ connect a peer", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        SectionLabel("CONVERSATIONS")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.conversations.isEmpty()) {
                item {
                    Text(
                        "No conversations yet — connect a peer to begin.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
            items(state.conversations, key = { it.id }) { conv ->
                ConversationRow(
                    conv = conv,
                    active = false,
                    online = state.convOrNull(conv.id)?.online == true,
                    avatar = state.convOrNull(conv.id)?.peerAvatarBitmap,
                    description = state.convOrNull(conv.id)?.peerDescription,
                    rttMs = state.convOrNull(conv.id)?.rttMs,
                    onClick = { state.openChatMobile(conv.id) },
                    onToggleEnabled = { state.setConversationEnabled(conv.id, !conv.enabled) },
                    onDelete = { state.deleteConversation(conv.id) },
                )
            }
        }
    }
}

@Composable
private fun MobileConnect(state: AzulaState) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", color = AzulaColors.textDim, fontSize = 20.sp, modifier = Modifier.clickable { state.mobileBack() })
            Text("connect a peer", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Start)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        ConnectScreen(state, Modifier.weight(1f))
    }
}
