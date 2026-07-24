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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.core.ROLE_MAILBOX
import dev.azula.link.AzulaLinks
import dev.azula.net.Frame
import dev.azula.persist.Persona
import dev.azula.state.AzulaState
import dev.azula.state.ConnectService
import dev.azula.state.toFrame
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaInputSurface
import dev.azula.theme.azulaMutedPanel
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private fun encodeAvatar(bytes: ByteArray): Pair<String, String>? =
    scaleImageForAvatar(bytes)?.let { Base64.encode(it.bytes) to it.mime }

@OptIn(ExperimentalEncodingApi::class)
private fun avatarBitmap(b64: String?) =
    b64?.let { runCatching { decodeImageBitmap(Base64.decode(it)) }.getOrNull() }

@Composable
fun SettingsScreen(state: AzulaState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<Persona?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showReveal by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var showDeviceLink by remember { mutableStateOf(false) }
    var showGrantLink by remember { mutableStateOf(false) }
    var showDeviceManagement by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(AzulaColors.bg1)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", color = AzulaColors.textDim, fontSize = 20.sp, modifier = Modifier.clickable { onBack() })
            Text("profiles & settings", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AzulaColors.border))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("YOUR PERSONAS", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            Text(
                "the identity you present when you connect. you choose which one — and which fields — to share each time.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (state.personas.isEmpty()) {
                Text("No personas yet.", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp)
            }
            state.personas.forEach { persona ->
                PersonaRow(
                    persona = persona,
                    isDefault = state.defaultPersonaId == persona.id,
                    onSetDefault = { state.setDefaultPersona(persona.id) },
                    onEdit = { editing = persona; showEditor = true },
                    onDelete = { state.deletePersona(persona.id) },
                )
            }
            Box(
                Modifier.fillMaxWidth().clip(AzulaShapes.rMd)
                    .background(AzulaColors.primaryWash).border(1.dp, AzulaColors.primaryEdge, AzulaShapes.rMd)
                    .clickable { editing = null; showEditor = true }
                    .padding(vertical = 11.dp)
                    .testTag("new-persona-button"),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ new persona", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text("TERMINAL", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            Row(
                Modifier.fillMaxWidth().azulaMutedPanel().padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.terminalSmartInput) "Smart input" else "Raw input (legacy)",
                        color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    )
                    Text(
                        "swipe typing, autocorrect, and word suggestions in the terminal. turn off if your keyboard misbehaves.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
                    )
                }
                Switch(checked = state.terminalSmartInput, onCheckedChange = { state.setTerminalSmartInput(it) })
            }

            Spacer(Modifier.height(8.dp))
            Text("BACKUP & RECOVERY", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            Text(
                "your identity is a 24-word recovery phrase shared across your devices. save it to add another device to this identity — or restore it here to enroll this one.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
            )
            BackupRow("Reveal recovery phrase", "24 words · store in a password manager", tag = "reveal-phrase-row") { showReveal = true }
            BackupRow("Restore from a phrase", "join this device to another identity", tag = "restore-phrase-row") { showRestore = true }

            Spacer(Modifier.height(8.dp))
            Text("DEVICE LINKING", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
            Text(
                "link this device to an identity by scanning a code instead of typing a recovery phrase — it joins without holding the identity's root secret.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
            )
            BackupRow("Link to existing identity", "show a code for another device to scan", tag = "device-link-row") { showDeviceLink = true }
            // Only a root-holding device can grant a link (device-linking spec: "a device
            // without one cannot be the granting side and the UI must not offer it").
            if (state.canGrantDeviceLinks()) {
                BackupRow("Link a new device", "paste a code shown on the new device", tag = "grant-link-row") { showGrantLink = true }
            }
            BackupRow("Manage devices", "rename or revoke devices on this identity", tag = "device-management-row") { showDeviceManagement = true }
        }
    }
    if (showEditor) {
        PersonaEditor(
            initial = editing,
            newId = { state.newPersonaId() },
            onSave = { state.upsertPersona(it); showEditor = false },
            onCancel = { showEditor = false },
        )
    }
    if (showReveal) RevealPhraseDialog(state) { showReveal = false }
    if (showRestore) RestorePhraseDialog(state) { showRestore = false }
    if (showDeviceLink) DeviceLinkDialog(state) { showDeviceLink = false }
    if (showGrantLink) GrantDeviceLinkDialog(state) { showGrantLink = false }
    if (showDeviceManagement) DeviceManagementDialog(state) { showDeviceManagement = false }
}

@Composable
private fun BackupRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().azulaMutedPanel()
            .clickable { onClick() }
            .padding(13.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
        }
        Text("›", color = AzulaColors.textDim, fontSize = 18.sp)
    }
}

/**
 * Reveal the 24-word recovery phrase, gated behind a secret-warning step. A device that
 * holds no root secret (QR-linked -- see the identity capability's MODIFIED "24-Word
 * BIP-39 Recovery Phrase Encoding" requirement, "Device keys have no phrase" scenario)
 * never has a phrase to show, permanently, unlike the transient "not bound yet" case --
 * [rootless] distinguishes the two so each gets its own explanation.
 */
@Composable
private fun RevealPhraseDialog(state: AzulaState, onClose: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val phrase = remember { state.exportRecoveryPhrase() }
    // exportRecoveryPhrase() is built directly from the transport's independently-nullable
    // root-secret entry (ConnectService.exportRecoveryPhrase) -- `phrase == null` IS "no
    // root secret", not something inferred from another signal. Paired with a known node
    // id, that's the permanent QR-linked case rather than "not bound yet".
    val rootless = remember { phrase == null && state.nodeId != null }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when {
                    rootless -> "No recovery phrase on this device"
                    revealed -> "Your recovery phrase"
                    else -> "Reveal recovery phrase?"
                },
            )
        },
        text = {
            when {
                rootless -> Text(
                    "This device joined the identity via a QR link, so the recovery phrase was " +
                        "never stored here — it lives only on devices that were set up with it, or " +
                        "that restored it. Check the phrase on one of those instead.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
                !revealed -> Text(
                    "These 24 words are your identity key. Anyone who has them can take over your identity — only store them in a password manager, and never share them.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
                phrase == null -> Text(
                    "Your key isn't ready yet. Connect once so the node comes online, then try again.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.fillMaxWidth().azulaInputSurface().padding(12.dp)
                            .testTag("recovery-phrase-text"),
                    ) {
                        Text(phrase, color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    Text(
                        if (state.copied) "✓ copied" else "copy to clipboard",
                        color = if (state.copied) AzulaColors.onlineBright else AzulaColors.pinkLight,
                        fontFamily = Fonts.mono, fontSize = 12.sp,
                        modifier = Modifier.clickable { clipboard.setText(AnnotatedString(phrase)); state.markCopied() },
                    )
                }
            }
        },
        confirmButton = {
            if (!rootless && !revealed && phrase != null) {
                TextButton(onClick = { revealed = true }, modifier = Modifier.testTag("reveal-phrase-confirm")) { Text("Reveal") }
            } else {
                TextButton(onClick = onClose, modifier = Modifier.testTag("reveal-phrase-confirm")) { Text("Done") }
            }
        },
        dismissButton = { if (!rootless && !revealed) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * Paste a recovery phrase to enroll this device into the identity it encodes
 * (multi-device-identity task 3.4: restore no longer replaces anything in place -- it
 * keeps this device's own node key and joins the identity as another device). Overwriting
 * an already-held *different* identity's root secret is the one genuinely destructive
 * case, so it's gated behind a second confirmation step (task 3.5a) -- detected via
 * [AzulaState.isDifferentIdentityRestore] before ever committing. A fresh device (nothing
 * held yet) or re-entering this identity's own phrase stays a single tap.
 */
@Composable
private fun RestorePhraseDialog(state: AzulaState, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var confirmingReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun commit() {
        working = true
        scope.launch {
            val ok = state.importRecoveryPhrase(input)
            working = false
            confirmingReplace = false
            if (ok) onClose() else error = true
        }
    }

    if (confirmingReplace) {
        AlertDialog(
            onDismissRequest = { confirmingReplace = false },
            title = { Text("Replace this device's identity?") },
            text = {
                Text(
                    "This device already holds a different identity. Restoring this phrase discards " +
                        "its root secret — that can't be undone without that identity's own recovery phrase.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = { commit() },
                    modifier = Modifier.testTag("restore-phrase-confirm-replace"),
                ) { Text(if (working) "Replacing…" else "Replace it") }
            },
            dismissButton = { TextButton(onClick = { confirmingReplace = false }) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Restore from recovery phrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste your 24-word recovery phrase. This device joins that identity as another " +
                        "device — it keeps its own key. If this device already holds a different identity, " +
                        "you'll be asked to confirm before its root secret is replaced.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
                EditorField("recovery phrase", input, "word word word …", tag = "restore-phrase-input") { input = it; error = false }
                if (error) Text("That isn't a valid recovery phrase.", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !working,
                onClick = {
                    if (state.isDifferentIdentityRestore(input) == true) {
                        confirmingReplace = true
                    } else {
                        commit()
                    }
                },
                modifier = Modifier.testTag("restore-phrase-confirm"),
            ) { Text(if (working) "Restoring…" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * New-device side of a QR-link enrollment (multi-device-identity task 6.1, spec's
 * "QR-Link Enrollment Grants a Certificate Without Root Authority"): generates this
 * device's `azl…` payload, shows it as a QR + copyable string, then the four
 * verification words the instant a root-holding device dials in -- before any grant is
 * possible, per the spec -- and finally the grant/reject outcome. This device never
 * holds the root secret afterward regardless of outcome (`ConnectService.beginDeviceLink`).
 */
@Composable
private fun DeviceLinkDialog(state: AzulaState, onClose: () -> Unit) {
    var name by remember { mutableStateOf("My device") }
    val clipboard = LocalClipboardManager.current

    fun closeAndReset() {
        state.cancelDeviceLink()
        state.resetDeviceLink()
        onClose()
    }

    AlertDialog(
        onDismissRequest = ::closeAndReset,
        title = { Text("Link to existing identity") },
        text = {
            when (state.deviceLinkStage) {
                ConnectService.DeviceLinkStage.IDLE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Scan the code this generates on the device that already holds your identity. " +
                            "This device joins without ever holding its root secret.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    EditorField("name shown to the other device", name, "e.g. my laptop", tag = "device-link-name") { name = it }
                }

                ConnectService.DeviceLinkStage.WAITING_FOR_SCAN -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.deviceLinkPayload?.let { payload ->
                        // multi-device-identity task 6.6: the QR encodes the deeplink URL, not
                        // the bare payload -- a bare azl… string is just inert text to a phone's
                        // camera app, but the wrapped https://azula.app/l/<payload> link is
                        // scannable and opens azula directly. The copyable string below stays
                        // the bare payload (still the paste-path fallback).
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(AzulaLinks.deviceLinkUrl(payload)) }
                        Box(Modifier.fillMaxWidth().azulaInputSurface().padding(12.dp).testTag("device-link-payload")) {
                            Text(payload, color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        Text(
                            if (state.copied) "✓ copied" else "copy to clipboard",
                            color = if (state.copied) AzulaColors.onlineBright else AzulaColors.pinkLight,
                            fontFamily = Fonts.mono, fontSize = 12.sp,
                            modifier = Modifier.clickable { clipboard.setText(AnnotatedString(payload)); state.markCopied() },
                        )
                    }
                    Text("Waiting for it to connect…", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp)
                }

                ConnectService.DeviceLinkStage.VERIFYING -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Confirm these four words match what's shown on the other device, then wait for it to confirm.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    Box(Modifier.fillMaxWidth().azulaInputSurface().padding(14.dp).testTag("device-link-words")) {
                        Text(
                            state.deviceLinkWords.joinToString("  ·  "),
                            color = AzulaColors.text0, fontFamily = Fonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        )
                    }
                }

                ConnectService.DeviceLinkStage.GRANTED -> Text(
                    "Linked! This device now holds a certificate for the identity.",
                    color = AzulaColors.onlineBright, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.testTag("device-link-granted"),
                )

                ConnectService.DeviceLinkStage.REJECTED -> Text(
                    "Link request was declined${state.deviceLinkRejectReason?.let { ": $it" } ?: "."}",
                    color = AzulaColors.error, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )

                ConnectService.DeviceLinkStage.FAILED -> Text(
                    "Couldn't link this device${state.deviceLinkRejectReason?.let { ": $it" } ?: "."}",
                    color = AzulaColors.error, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            when (state.deviceLinkStage) {
                ConnectService.DeviceLinkStage.IDLE -> TextButton(
                    onClick = { state.beginDeviceLink(name.ifBlank { "My device" }) },
                    modifier = Modifier.testTag("device-link-generate"),
                ) { Text("Generate code") }
                ConnectService.DeviceLinkStage.GRANTED,
                ConnectService.DeviceLinkStage.REJECTED,
                ConnectService.DeviceLinkStage.FAILED,
                -> TextButton(onClick = ::closeAndReset, modifier = Modifier.testTag("device-link-done")) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.deviceLinkStage == ConnectService.DeviceLinkStage.WAITING_FOR_SCAN ||
                state.deviceLinkStage == ConnectService.DeviceLinkStage.VERIFYING ||
                state.deviceLinkStage == ConnectService.DeviceLinkStage.IDLE
            ) {
                TextButton(onClick = ::closeAndReset) { Text("Cancel") }
            }
        },
    )
}

/**
 * Root-holding side of a QR-link enrollment (multi-device-identity task 6.2): paste the
 * new device's `azl…` code, dial it, then show the same four verification words plus the
 * requested roles (bit 0 = mailbox) for explicit confirmation -- only that confirmation
 * ever produces a certificate; cancelling sends `LinkReject` (`ConnectService.confirmGrantLink`/
 * `cancelGrantLink`). Not offered at all unless [AzulaState.canGrantDeviceLinks] is true --
 * issuing requires the root secret.
 */
@Composable
private fun GrantDeviceLinkDialog(state: AzulaState, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }

    fun closeAndReset() {
        state.cancelGrantLink()
        state.resetGrantLink()
        onClose()
    }

    AlertDialog(
        onDismissRequest = ::closeAndReset,
        title = { Text("Link a new device") },
        text = {
            when (state.grantStage) {
                ConnectService.GrantStage.IDLE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste the code shown on the device you want to link.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    EditorField("link code", input, "azl…", tag = "grant-link-input") { input = it }
                }

                ConnectService.GrantStage.DIALING, ConnectService.GrantStage.AWAITING_HELLO -> Text(
                    "Connecting…", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp,
                )

                ConnectService.GrantStage.CONFIRMING -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Confirm these four words match what's shown on \"${state.grantPeerName}\", " +
                            "then grant it access" +
                            (if ((state.grantPeerRoles and ROLE_MAILBOX) != 0) " — including the mailbox role" else "") +
                            ".",
                        color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    Box(Modifier.fillMaxWidth().azulaInputSurface().padding(14.dp).testTag("grant-link-words")) {
                        Text(
                            state.grantWords.joinToString("  ·  "),
                            color = AzulaColors.text0, fontFamily = Fonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        )
                    }
                }

                ConnectService.GrantStage.GRANTED -> Text(
                    "Linked! \"${state.grantPeerName}\" now holds a certificate for this identity.",
                    color = AzulaColors.onlineBright, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.testTag("grant-link-granted"),
                )

                ConnectService.GrantStage.REJECTED -> Text(
                    "Declined — nothing was granted.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )

                ConnectService.GrantStage.FAILED -> Text(
                    "Couldn't link that device${state.grantFailReason?.let { ": $it" } ?: "."}",
                    color = AzulaColors.error, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            when (state.grantStage) {
                ConnectService.GrantStage.IDLE -> TextButton(
                    enabled = input.isNotBlank(),
                    onClick = { state.beginGrantDial(input.trim()) },
                    modifier = Modifier.testTag("grant-link-connect"),
                ) { Text("Connect") }
                ConnectService.GrantStage.CONFIRMING -> TextButton(
                    onClick = { state.confirmGrantLink() },
                    modifier = Modifier.testTag("grant-link-confirm"),
                ) { Text("Grant") }
                ConnectService.GrantStage.GRANTED,
                ConnectService.GrantStage.REJECTED,
                ConnectService.GrantStage.FAILED,
                -> TextButton(onClick = ::closeAndReset, modifier = Modifier.testTag("grant-link-done")) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.grantStage != ConnectService.GrantStage.GRANTED) {
                TextButton(onClick = ::closeAndReset) { Text("Cancel") }
            }
        },
    )
}

/**
 * Device management screen (multi-device-identity task 6.3): lists the identity's current
 * device set from the fold ([AzulaState.devices]/[AzulaState.refreshDevices]), lets the user
 * rename any device *locally only* (never mints a certificate -- [AzulaState.renameDeviceLocally]),
 * and -- only when this device holds the root secret ([AzulaState.canRevokeDevices], the same
 * requirement [GrantDeviceLinkDialog] gates on) -- revoke another device, appending a
 * `device_revoke` entry. A QR-linked device sees every row but no "revoke" control at all: it
 * holds no root secret and the device-linking spec requires the UI not offer one.
 */
@Composable
private fun DeviceManagementDialog(state: AzulaState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { state.refreshDevices() }
    var editingHex by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    var revokingHex by remember { mutableStateOf<String?>(null) }
    val canRevoke = state.canRevokeDevices()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.devices.isEmpty()) {
                    Text("No devices yet.", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp)
                }
                state.devices.forEach { row ->
                    DeviceManagementRow(
                        row = row,
                        canRevoke = canRevoke && !row.isSelf,
                        onRename = { editingHex = row.devicePkHex; editingName = row.localName ?: row.certName },
                        onRevoke = { revokingHex = row.devicePkHex },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("device-management-done")) { Text("Done") } },
    )

    editingHex?.let { hex ->
        AlertDialog(
            onDismissRequest = { editingHex = null },
            title = { Text("Rename device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This name is local to this device only — it never changes the device's certificate.",
                        color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    EditorField("display name", editingName, "e.g. my laptop", tag = "device-rename-input") { editingName = it }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { state.renameDeviceLocally(hex, editingName); editingHex = null },
                    modifier = Modifier.testTag("device-rename-save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingHex = null }) { Text("Cancel") } },
        )
    }

    revokingHex?.let { hex ->
        AlertDialog(
            onDismissRequest = { revokingHex = null },
            title = { Text("Revoke this device?") },
            text = {
                Text(
                    "It will no longer be able to sync, send, or receive as this identity. This can't be undone.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { state.revokeDevice(hex); revokingHex = null } },
                    modifier = Modifier.testTag("device-revoke-confirm"),
                ) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revokingHex = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceManagementRow(row: ConnectService.DeviceRow, canRevoke: Boolean, onRename: () -> Unit, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().azulaMutedPanel().padding(11.dp).testTag("device-row-${row.devicePkHex}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val label = (row.localName ?: row.certName).ifBlank { row.devicePkHex.take(8) } +
                (if (row.isSelf) "  ·  this device" else "") +
                (if (row.isMailbox) "  ·  mailbox" else "")
            Text(label, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(row.devicePkHex.take(16) + "…", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
        }
        Text(
            "rename", color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontSize = 11.sp,
            modifier = Modifier.clickable { onRename() }.testTag("device-rename-${row.devicePkHex}"),
        )
        if (canRevoke) {
            Text(
                "revoke", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp,
                modifier = Modifier.clickable { onRevoke() }.testTag("device-revoke-${row.devicePkHex}"),
            )
        }
    }
}

@Composable
private fun PersonaRow(
    persona: Persona,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = remember(persona.avatarB64) { avatarBitmap(persona.avatarB64) }
    Row(
        Modifier.fillMaxWidth().azulaMutedPanel()
            .clickable { onEdit() }
            .padding(11.dp)
            .testTag("persona-row-${persona.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AvatarBox(bitmap, persona.name.firstOrNull()?.uppercaseChar()?.toString() ?: "•", dev.azula.model.ConversationKind.PEER)
        Column(Modifier.weight(1f)) {
            Text(persona.name, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                persona.description?.takeIf { it.isNotBlank() } ?: "no description",
                color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp,
            )
        }
        Box(
            Modifier.clip(AzulaShapes.rXs)
                .border(1.dp, if (isDefault) AzulaColors.pink else AzulaColors.border4, AzulaShapes.rXs)
                .clickable { onSetDefault() }.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(if (isDefault) "default ✓" else "set default", color = if (isDefault) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 10.sp)
        }
        ConvOverflowMenu(
            enabled = true,
            onToggleEnabled = null,
            onDelete = onDelete,
            deleteTitle = "Delete persona?",
            deleteBody = "This removes the profile from this device and can't be undone. " +
                "Conversations you already started aren't affected.",
        )
    }
}

@Composable
private fun PersonaEditor(
    initial: Persona?,
    newId: () -> String,
    onSave: (Persona) -> Unit,
    onCancel: () -> Unit,
) {
    val id = remember { initial?.id ?: newId() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var avatarB64 by remember { mutableStateOf(initial?.avatarB64) }
    var mime by remember { mutableStateOf(initial?.mime) }
    val preview = remember(avatarB64) { avatarBitmap(avatarB64) }
    val launchPicker = rememberFilePicker { picked ->
        if (picked.mime.startsWith("image/")) {
            encodeAvatar(picked.bytes)?.let { (b64, m) -> avatarB64 = b64; mime = m }
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial == null) "New persona" else "Edit persona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarBox(preview, name.firstOrNull()?.uppercaseChar()?.toString() ?: "•", dev.azula.model.ConversationKind.PEER, size = 48.dp)
                    Box(
                        Modifier.clip(AzulaShapes.rSm).border(1.dp, AzulaColors.border4, AzulaShapes.rSm)
                            .clickable { launchPicker() }.padding(horizontal = 11.dp, vertical = 7.dp),
                    ) {
                        Text(if (avatarB64 == null) "pick picture" else "change picture", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 11.sp)
                    }
                    if (avatarB64 != null) {
                        Text("remove", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp, modifier = Modifier.clickable { avatarB64 = null; mime = null })
                    }
                }
                EditorField("name", name, "your name", tag = "persona-name-input") { name = it }
                EditorField("description", description, "a short bio (optional)") { description = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(Persona(id = id, name = name.trim(), description = description.trim().ifBlank { null }, avatarB64 = avatarB64, mime = mime))
                },
                modifier = Modifier.testTag("persona-editor-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun EditorField(label: String, value: String, placeholder: String, tag: String? = null, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 9.sp)
        Box(
            Modifier.fillMaxWidth().azulaInputSurface(AzulaShapes.rSm).padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp),
                cursorBrush = SolidColor(AzulaColors.pink),
                modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                    inner()
                },
            )
        }
    }
}

/**
 * The reusable "choose a persona, choose which fields" picker: persona chips
 * plus three field toggles. Used inline (never as a blocking gate) by both the
 * outbound share-a-profile step (Connect.kt) and the inbound accept-with-profile
 * step (Invites.kt's `InviteReviewSheet`). Purely presentational — the caller
 * owns the selection state and builds the resulting [Frame.Profile] itself via
 * [dev.azula.state.toFrame] when it actually needs one.
 */
@Composable
fun PersonaPicker(
    personas: List<Persona>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    shareName: Boolean,
    onShareNameChange: (Boolean) -> Unit,
    sharePic: Boolean,
    onSharePicChange: (Boolean) -> Unit,
    shareDesc: Boolean,
    onShareDescChange: (Boolean) -> Unit,
) {
    val selected = personas.firstOrNull { it.id == selectedId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (personas.isEmpty()) {
            Text("No personas yet — create one in Settings to share a profile.", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.5.sp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                personas.forEach { p ->
                    val on = p.id == selectedId
                    Box(
                        Modifier.clip(AzulaShapes.rSm)
                            .background(if (on) AzulaColors.primarySelected else Color.Transparent)
                            .border(1.dp, if (on) AzulaColors.pink else AzulaColors.border4, AzulaShapes.rSm)
                            .clickable { onSelect(p.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(p.name, color = if (on) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp)
                    }
                }
            }
            ShareToggle("share name", shareName, onChange = onShareNameChange)
            ShareToggle("share picture", sharePic && selected?.avatarB64 != null, enabled = selected?.avatarB64 != null, onChange = onSharePicChange)
            ShareToggle("share description", shareDesc && selected?.description != null, enabled = selected?.description != null, onChange = onShareDescChange)
        }
    }
}

@Composable
private fun ShareToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) AzulaColors.text2 else AzulaColors.textFaint, fontFamily = Fonts.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
