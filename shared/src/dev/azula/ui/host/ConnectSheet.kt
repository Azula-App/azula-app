package dev.azula.ui.host

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.host.GrantTtl
import dev.azula.host.HostSetupService
import dev.azula.host.computeSnippet
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaInputSurface
import dev.azula.theme.azulaMutedPanel
import dev.azula.ui.QrCode
import dev.azula.vmhost.MintedGrant

/**
 * S5 · connect a cloud session. A bottom sheet in the style of `CreateInviteSheet`: mints a grant
 * the moment it opens (6 h by default; the chips re-mint, revoking the one they replace), then
 * shows the snippet to paste into a cloud session, as text (copy, share) or a QR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(svc: HostSetupService, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("connect a cloud session", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            ConnectContent(svc)
        }
    }
}

@Composable
internal fun ConnectContent(svc: HostSetupService) {
    val clipboard = LocalClipboardManager.current
    var ttl by remember { mutableStateOf(GrantTtl.SIX_HOURS) }
    var minted by remember { mutableStateOf<MintedGrant?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var minting by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    // A new chip supersedes the previous grant: revoke it, then mint with the new lifetime.
    LaunchedEffect(ttl) {
        minting = true
        failure = null
        copied = false
        minted?.let { svc.revoke(it.grant.id) }
        minted = null
        svc.mint(ttl)
            .onSuccess { minted = it }
            .onFailure { failure = it.message ?: "couldn't mint a grant" }
        minting = false
    }

    HostLabel("VALID FOR")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GrantTtl.entries.forEach { e ->
            val on = e == ttl
            Box(
                Modifier.clip(AzulaShapes.rSm)
                    .background(if (on) AzulaColors.primarySelected else Color.Transparent)
                    .border(1.dp, if (on) AzulaColors.pink else AzulaColors.border4, AzulaShapes.rSm)
                    .clickable { ttl = e }.padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("host-ttl-${e.arg}"),
            ) { Text(e.label, color = if (on) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp) }
        }
    }

    val m = minted
    when {
        failure != null -> Text(failure!!, color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.5.sp, modifier = Modifier.testTag("host-mint-error"))
        minting || m == null -> HostMono("minting…", tag = "host-minting")
        else -> {
            val snippet = remember(m.token) { computeSnippet(m.token) }
            Text(
                m.grant.label, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
                modifier = Modifier.testTag("host-grant-label"),
            )
            Box(Modifier.fillMaxWidth().azulaInputSurface().padding(12.dp)) {
                Text(snippet, color = AzulaColors.text2, fontFamily = Fonts.mono, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.testTag("host-snippet"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    HostButton(if (copied) "✓ copied" else "copy", "host-copy", primary = false) {
                        clipboard.setText(AnnotatedString(snippet)); copied = true
                    }
                }
                Box(Modifier.weight(1f)) { HostButton("share", "host-share", primary = false) { svc.share(snippet) } }
                Box(Modifier.weight(1f)) { HostButton(if (showQr) "hide qr" else "qr", "host-qr", primary = false) { showQr = !showQr } }
            }
            if (showQr) Box(Modifier.fillMaxWidth().testTag("host-qr-code"), contentAlignment = Alignment.Center) { QrCode(snippet) }
            HostBody("this token lets whoever holds it run commands in your vm until it expires. revoke it any time from the list.")
        }
    }
    Spacer(Modifier.height(2.dp))
    Column(Modifier.fillMaxWidth().azulaMutedPanel().padding(12.dp).testTag("host-relay-note"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HostBody("the cloud environment must allow the n0 relay hosts")
        Text(
            "how ›", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontSize = 12.sp,
            modifier = Modifier.clickable { svc.openDocs() }.testTag("host-relay-docs"),
        )
    }
}
