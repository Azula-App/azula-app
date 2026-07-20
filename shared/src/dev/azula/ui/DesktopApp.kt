package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

@Composable
fun DesktopApp(state: AzulaState) {
    // Transparent so the app-root AzulaBrushes.appBackground wash (applied in
    // App.kt) shows through, matching MobileApp.kt. The content row is inset for
    // safe areas (iPad notch / home indicator); on JVM/desktop these insets are
    // empty, so it's a no-op. Sidebar and ChatPane's panes each own their fill
    // (bgSidebar / SettingsScreen's bg1 / transparent-over-wash), so this Box no
    // longer needs to paint anything itself.
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Sidebar(state, Modifier.width(248.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(AzulaColors.border))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (val active = state.desktopActive) {
                    "connect" -> ConnectPane(state)
                    "settings" -> SettingsScreen(state, onBack = { state.openConnectDesktop() })
                    "invites" -> InvitesPane(state)
                    else -> ChatPane(state, active)
                }
            }
        }
        FullscreenMediaOverlay(state)
        SharePickerOverlay(state)
    }
}

@Composable
private fun ConnectPane(state: AzulaState) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox(GlyphStyle.PINK, "+")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("connect a peer", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(AzulaColors.pink)
                    Spacer(Modifier.width(7.dp))
                    Text("share your code · paste a peer's", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        ConnectScreen(state, Modifier.weight(1f), useBottomSheetForCreateInvite = false)
    }
}
