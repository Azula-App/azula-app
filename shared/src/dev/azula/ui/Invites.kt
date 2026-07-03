package dev.azula.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.state.AzulaState
import dev.azula.state.PendingConnectionRequest
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts
import kotlinx.coroutines.launch

/**
 * A minimal invites inbox: pending strangers to accept/decline, invites this
 * device has issued (with revoke), and a one-tap "new invite" action. Kept
 * simple by design -- the polished sheets/animations/settings this screen
 * eventually wants are a follow-up; this just gets the whole invite flow
 * reachable and usable end to end.
 */
@Composable
fun InvitesScreen(state: AzulaState, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var lastCreated by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", color = AzulaColors.textDim, fontSize = 20.sp, modifier = Modifier.clickable { onBack() })
            Text("invites", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                        .background(AzulaColors.bg2)
                        .clickable(enabled = !creating) {
                            creating = true
                            scope.launch {
                                lastCreated = state.createInvite(expiresInSeconds = 24L * 3600, sign = false, singleUse = false)
                                creating = false
                            }
                        }
                        .padding(14.dp),
                ) {
                    Text(
                        if (creating) "minting…" else "+ new invite (24h)",
                        color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    )
                }
            }
            lastCreated?.let { encoded ->
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(AzulaColors.bg2)
                            .clickable { clipboard.setText(AnnotatedString(encoded)); state.markCopied() }.padding(12.dp),
                    ) {
                        Text(if (state.copied) "✓ copied" else "tap to copy", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(encoded, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 11.sp)
                    }
                }
            }
            if (state.pendingInvites.isNotEmpty()) {
                item { SectionLabel("PENDING") }
                items(state.pendingInvites, key = { it.nodeId }) { req ->
                    PendingRequestRow(req, onAccept = { state.acceptInvite(req) }, onDecline = { state.declineInvite(req) })
                }
            }
            if (state.issuedInvites.isNotEmpty()) {
                item { SectionLabel("ISSUED") }
                items(state.issuedInvites, key = { it.id }) { inv ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AzulaColors.bg2)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(inv.label ?: inv.id, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp)
                            Text(
                                if (inv.consumed) "consumed" else if (inv.expiresAt == 0L) "never expires" else "expires ${inv.expiresAt}",
                                color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 10.sp,
                            )
                        }
                        Text("revoke", color = AzulaColors.pink, fontFamily = Fonts.ui, fontSize = 12.sp, modifier = Modifier.clickable { state.revokeInvite(inv.id) })
                    }
                }
            }
            if (state.pendingInvites.isEmpty() && state.issuedInvites.isEmpty()) {
                item {
                    Text(
                        "No invites yet — mint one above to bring in a peer.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingRequestRow(req: PendingConnectionRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AzulaColors.bg2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(req.peerCode, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp)
            Text(
                if (req.unverified) "unverified — no invite presented" else "invite verified",
                color = if (req.unverified) AzulaColors.pink else AzulaColors.onlineBright,
                fontFamily = Fonts.mono, fontSize = 10.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("decline", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontSize = 12.sp, modifier = Modifier.clickable { onDecline() })
            Text("accept", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.clickable { onAccept() })
        }
    }
}
