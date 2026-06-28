package dev.azula.ui

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
        when (state.mScreen) {
            MScreen.HOME -> MobileHome(state)
            MScreen.CONNECT -> MobileConnect(state)
            MScreen.CHAT -> ChatPane(state, state.mActive, onBack = { state.mobileBack() })
        }
    }
}

@Composable
private fun MobileHome(state: AzulaState) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Brand()
                Text("◉ online", color = AzulaColors.online, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
            Spacer(Modifier.height(14.dp))
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
            items(state.conversations, key = { it.id }) { conv ->
                ConversationRow(conv = conv, active = false, online = state.conv(conv.id).online) { state.openChatMobile(conv.id) }
            }
        }
    }
}

@Composable
private fun MobileConnect(state: AzulaState) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 14.dp),
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
