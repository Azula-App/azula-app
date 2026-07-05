package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.a2ui.A2uiTokens
import dev.azula.model.ConversationKind
import dev.azula.state.AzulaState
import dev.azula.theme.Fonts

/**
 * Root-level "Share to…" picker for an inbound OS share (Android `SEND`/
 * `SEND_MULTIPLE` intent, iOS Share Extension via `ShareInbox.drainShareInbox`).
 * Shown whenever [AzulaState.pendingShare] is non-null: a neon-glass sheet over a
 * scrim, previewing the staged items (reusing [PendingMediaPreview]) and the
 * conversation list (filtered to non-`TERMINAL` -- you can't share media/text
 * into a remote-shell conversation) to pick a destination. Tapping the scrim or
 * the ✕ cancels via [AzulaState.cancelShare]; picking a row delivers via
 * [AzulaState.shareTo]. Mounted at the root of both `MobileApp` and `DesktopApp`,
 * next to [FullscreenMediaOverlay] -- see the back-handler chain in `MobileApp.kt`
 * (share-picker wins over fullscreen wins over nav).
 */
@Composable
fun SharePickerOverlay(state: AzulaState) {
    val items = state.pendingShare ?: return
    val blockPropagation = remember { MutableInteractionSource() }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { state.cancelShare() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(A2uiTokens.rXl)
                .background(A2uiTokens.surface)
                .border(1.dp, A2uiTokens.outline, A2uiTokens.rXl)
                .clickable(indication = null, interactionSource = blockPropagation) { /* consume: don't dismiss */ }
                .testTag("share-picker"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Share to…", color = A2uiTokens.contentBright, fontFamily = Fonts.ui, fontSize = 16.sp)
                Text(
                    "✕", color = A2uiTokens.muted, fontSize = 18.sp,
                    modifier = Modifier.clickable { state.cancelShare() }.testTag("share-picker-close"),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(A2uiTokens.outlineSoft))
            if (items.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(items, key = { it.name + it.bytes.size }) { file ->
                        PendingMediaPreview(file, onCancel = {})
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(A2uiTokens.outlineSoft))
            }
            ConversationList(
                state = state,
                activeId = null,
                onSelect = { state.shareTo(it) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                filter = { it.kind != ConversationKind.TERMINAL },
            )
        }
    }
}
