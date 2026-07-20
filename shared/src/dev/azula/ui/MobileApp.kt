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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.state.AzulaState
import dev.azula.state.MScreen
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaGlowHalo

@Composable
fun MobileApp(state: AzulaState) {
    // The "Share to…" picker (an inbound OS share) owns the system back gesture
    // first -- it should cancel the share, not dismiss whatever else is open
    // underneath it. Registered above (so it wins over) the other handlers below.
    PlatformBackHandler(enabled = state.pendingShare != null) { state.cancelShare() }
    // The fullscreen media viewer, when open, owns the system back gesture --
    // it should dismiss the overlay and return to chat rather than navigate the
    // mobile nav underneath it. Registered above (so it wins over) the nav
    // back-handler below.
    PlatformBackHandler(enabled = state.pendingShare == null && state.fullscreenMedia != null) { state.dismissFullscreenMedia() }
    // System back (e.g. Android's back button / gesture) navigates within the
    // app's mobile nav instead of exiting it.
    PlatformBackHandler(
        enabled = state.pendingShare == null && state.fullscreenMedia == null && state.mScreen != MScreen.HOME,
    ) { state.mobileBack() }
    Box(Modifier.fillMaxSize()) {
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
        SharePickerOverlay(state)
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
                    Modifier.fillMaxWidth().clip(AzulaShapes.rMd)
                        .background(AzulaColors.primarySelected).border(1.dp, AzulaColors.primaryEdge, AzulaShapes.rMd)
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
                Modifier.fillMaxWidth().azulaGlowHalo()
                    .clickable { state.openConnectMobile() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ connect a peer", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        SectionLabel("CONVERSATIONS")
        ConversationList(
            state = state,
            activeId = null,
            onSelect = state::openChatMobile,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
        )
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
