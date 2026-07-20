package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.link.AzulaLinks
import dev.azula.link.InviteCodec
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaInputSurface
import kotlinx.coroutines.launch

private enum class InviteExpiry(val label: String, val seconds: Long?, val summary: String) {
    ONE_HOUR("1h", 3600L, "expires in 1 hour"),
    ONE_DAY("24h", 86_400L, "expires in 24 hours"),
    ONE_WEEK("7d", 604_800L, "expires in 7 days"),
    NEVER("no expiry", null, "never expires"),
}

/** Mobile: [CreateInviteContent] hosted in a Material3 bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInviteSheet(state: AzulaState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("create an invite", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            CreateInviteContent(state)
        }
    }
}

/**
 * The invite-minting form: expiry / signed / single-use, a create action
 * ([AzulaState.createInvite]), then the result -- a scannable [QrCode] of
 * [AzulaLinks.inviteLink], the fingerprint, a copy-link action, and the raw
 * encoded string behind a small disclosure. Shared by [CreateInviteSheet]
 * (mobile) and the desktop inline pane (Connect.kt's `ConnectPane`).
 */
@Composable
fun CreateInviteContent(state: AzulaState) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var expiry by remember { mutableStateOf(InviteExpiry.ONE_DAY) }
    var signed by remember { mutableStateOf(false) }
    var singleUse by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showRawCode by remember { mutableStateOf(false) }

    val encoded = result
    if (encoded != null) {
        val link = AzulaLinks.inviteLink(encoded)
        val fingerprint = remember(encoded) { InviteCodec.decode(encoded)?.fingerprint }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(link) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                fingerprint?.let { Text("fingerprint $it", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp) }
                Text(expiry.summary, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
            Box(
                Modifier.fillMaxWidth().azulaInputSurface()
                    .clickable { clipboard.setText(AnnotatedString(link)); state.markCopied() }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.copied) "✓ copied" else "copy invite link",
                    color = if (state.copied) AzulaColors.onlineBright else AzulaColors.pinkLight,
                    fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                )
            }
            Text(
                (if (showRawCode) "raw code ⌄" else "raw code ›"),
                color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
                modifier = Modifier.clickable { showRawCode = !showRawCode },
            )
            if (showRawCode) {
                Text(encoded, color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("EXPIRES", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InviteExpiry.entries.forEach { e ->
                val on = e == expiry
                Box(
                    Modifier.clip(AzulaShapes.rSm)
                        .background(if (on) AzulaColors.primarySelected else Color.Transparent)
                        .border(1.dp, if (on) AzulaColors.pink else AzulaColors.border4, AzulaShapes.rSm)
                        .clickable { expiry = e }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(e.label, color = if (on) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp)
                }
            }
        }
        InviteToggleRow("sign invite", "lets the redeemer verify it came from you before dialing", signed) { signed = it }
        InviteToggleRow("single-use", "stops working after the first accept", singleUse) { singleUse = it }
        if (error) Text("couldn't create the invite — try again.", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.5.sp)
        Box(
            Modifier.fillMaxWidth().clip(AzulaShapes.rMd).background(PinkButtonBrush)
                .clickable(enabled = !creating) {
                    creating = true
                    error = false
                    scope.launch {
                        val created = state.createInvite(expiresInSeconds = expiry.seconds, sign = signed, singleUse = singleUse, label = null)
                        creating = false
                        if (created != null) result = created else error = true
                    }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (creating) "minting…" else "create invite", color = Color.White, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun InviteToggleRow(label: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AzulaColors.text2, fontFamily = Fonts.ui, fontSize = 13.sp)
            Text(subtitle, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 10.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
