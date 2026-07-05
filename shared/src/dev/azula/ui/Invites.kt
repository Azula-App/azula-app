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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.epochSeconds
import dev.azula.persist.IssuedInvite
import dev.azula.state.AzulaState
import dev.azula.state.PendingConnectionRequest
import dev.azula.state.toFrame
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts

/** Mobile: header + [InvitesContent]. */
@Composable
fun InvitesScreen(state: AzulaState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", color = AzulaColors.textDim, fontSize = 20.sp, modifier = Modifier.clickable { onBack() })
            Text("invites", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        InvitesContent(state, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Desktop: header (no back — the sidebar switches panes) + [InvitesContent]. */
@Composable
fun InvitesPane(state: AzulaState) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AzulaColors.bg2, AzulaColors.bg1)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBox(GlyphStyle.PINK, "✉")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("invites", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(AzulaColors.pink)
                    Spacer(Modifier.width(7.dp))
                    Text("requests to accept · links you've minted", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        InvitesContent(state, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Two sections: pending strangers awaiting accept/decline ("Requests" —
 * batch actions when more than one), and invites this device has minted
 * ("My invites", with revoke). Tapping a pending row opens [InviteReviewSheet].
 * Shared by [InvitesScreen] (mobile) and [InvitesPane] (desktop).
 */
@Composable
fun InvitesContent(state: AzulaState, modifier: Modifier = Modifier) {
    var reviewing by remember { mutableStateOf<PendingConnectionRequest?>(null) }
    reviewing?.let { req ->
        if (state.pendingInvites.none { it === req }) {
            reviewing = null // accepted/declined elsewhere (e.g. batch action) -- close automatically
        } else {
            InviteReviewSheet(state, req, onDismiss = { reviewing = null })
        }
    }

    LazyColumn(modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.pendingInvites.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("REQUESTS")
                    if (state.pendingInvites.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("decline all", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontSize = 11.sp, modifier = Modifier.clickable { state.declineAllInvites() })
                            Text("accept all", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.clickable { state.acceptAllInvites() })
                        }
                    }
                }
            }
            items(state.pendingInvites, key = { it.nodeId }) { req ->
                PendingRequestRow(req, onClick = { reviewing = req })
            }
        }
        item { SectionLabel("MY INVITES") }
        if (state.issuedInvites.isEmpty()) {
            item {
                Text(
                    "No invites yet — mint one from the connect screen to bring in a peer.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(state.issuedInvites, key = { it.id }) { inv -> IssuedInviteRow(inv, onRevoke = { state.revokeInvite(inv.id) }) }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PendingRequestRow(req: PendingConnectionRequest, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AzulaColors.bg2)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            GlyphBox(if (req.unverified) GlyphStyle.PEER else GlyphStyle.PINK, req.peerCode.firstOrNull()?.uppercaseChar()?.toString() ?: "•", size = 34.dp)
            Column {
                Text(req.peerCode, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(relativeTime(req.receivedAt), color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 10.sp)
                    Text(
                        if (req.unverified) "· unverified" else "· invite verified",
                        color = if (req.unverified) AzulaColors.pink else AzulaColors.onlineBright,
                        fontFamily = Fonts.mono, fontSize = 10.sp,
                    )
                }
            }
        }
        Text("review ›", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontSize = 12.sp)
    }
}

@Composable
private fun IssuedInviteRow(inv: IssuedInvite, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AzulaColors.bg2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(inv.label ?: inv.id, color = AzulaColors.text0, fontFamily = Fonts.mono, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(expiryLabel(inv), color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 10.sp)
                if (inv.flags and 0x01 != 0) Text("· signed", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 10.sp)
                if (inv.flags and 0x02 != 0) Text("· single-use", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 10.sp)
            }
        }
        if (!inv.consumed) {
            Text("revoke", color = AzulaColors.pink, fontFamily = Fonts.ui, fontSize = 12.sp, modifier = Modifier.clickable { onRevoke() })
        }
    }
}

private fun expiryLabel(inv: IssuedInvite): String = when {
    inv.consumed -> "consumed"
    inv.expiresAt == 0L -> "never expires"
    epochSeconds() >= inv.expiresAt -> "expired"
    else -> "expires in " + relativeDuration(inv.expiresAt - epochSeconds())
}

/** "3m ago" / "2h ago" / "5d ago" -- coarse, no need for anything finer here. */
private fun relativeTime(epochSecondsAt: Long): String {
    val delta = (epochSeconds() - epochSecondsAt).coerceAtLeast(0)
    return if (delta < 60) "just now" else relativeDuration(delta) + " ago"
}

private fun relativeDuration(seconds: Long): String = when {
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h"
    else -> "${seconds / 86_400}d"
}

/**
 * Review one pending request: peer code, verification status, an inline
 * (never required) [PersonaPicker] to share a name back, and Accept/Decline.
 * "Accept as guest" — no persona picked — is always available; sharing is a
 * pure opt-in, matching the outbound connect flow's same rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteReviewSheet(state: AzulaState, req: PendingConnectionRequest, onDismiss: () -> Unit) {
    var showShareOptions by remember { mutableStateOf(false) }
    var selectedPersonaId by remember { mutableStateOf(state.defaultPersonaId ?: state.personas.firstOrNull()?.id) }
    var shareName by remember { mutableStateOf(true) }
    var sharePic by remember { mutableStateOf(true) }
    var shareDesc by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(req.peerCode, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                if (req.unverified) {
                    "This peer didn't present a valid invite — treat it as unverified. Legacy connections are allowed for now; you can decline if you don't recognize it."
                } else {
                    "This peer presented a valid invite you issued."
                },
                color = if (req.unverified) AzulaColors.pink else AzulaColors.onlineBright,
                fontFamily = Fonts.ui, fontSize = 12.5.sp, lineHeight = 18.sp,
            )
            Text(
                if (showShareOptions) "sharing a profile back ⌄" else "share a profile back? (optional) ›",
                color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp,
                modifier = Modifier.clickable { showShareOptions = !showShareOptions },
            )
            if (showShareOptions) {
                PersonaPicker(
                    personas = state.personas,
                    selectedId = selectedPersonaId,
                    onSelect = { selectedPersonaId = it },
                    shareName = shareName, onShareNameChange = { shareName = it },
                    sharePic = sharePic, onSharePicChange = { sharePic = it },
                    shareDesc = shareDesc, onShareDescChange = { shareDesc = it },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AzulaColors.inputBg)
                        .clickable { state.declineInvite(req); onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("decline", color = AzulaColors.textDim, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(PinkButtonBrush)
                        .clickable {
                            // Share our name back by default; picture/description
                            // stay opt-in behind the expanded share panel.
                            val persona = state.personaById(selectedPersonaId)
                            val profile = persona?.let {
                                if (showShareOptions) it.toFrame(shareName, sharePic, shareDesc)
                                else it.toFrame(shareName = true, sharePic = false, shareDesc = false)
                            }
                            state.acceptInvite(req, profile)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (showShareOptions) "share & accept" else "accept as guest",
                        color = Color.White, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
