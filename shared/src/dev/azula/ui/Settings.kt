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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.net.Frame
import dev.azula.persist.Persona
import dev.azula.state.AzulaState
import dev.azula.state.toFrame
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts
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
            Text("YOUR PERSONAS", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
            Text(
                "the identity you present when you connect. you choose which one — and which fields — to share each time.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
            )
            if (state.personas.isEmpty()) {
                Text("No personas yet.", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 12.sp)
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(Color(0x14FF2D9B)).border(1.dp, Color(0x66FF2D9B), RoundedCornerShape(11.dp))
                    .clickable { editing = null; showEditor = true }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ new persona", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text("BACKUP & RECOVERY", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
            Text(
                "your identity is a single device key. save your recovery phrase to move it to a new device — or restore it after reinstalling.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
            )
            BackupRow("Reveal recovery phrase", "24 words · store in a password manager") { showReveal = true }
            BackupRow("Restore from a phrase", "replace this device's identity") { showRestore = true }
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
}

@Composable
private fun BackupRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AzulaColors.bg2)
            .border(1.dp, AzulaColors.border2, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp)
        }
        Text("›", color = AzulaColors.textDim, fontSize = 18.sp)
    }
}

/** Reveal the 24-word recovery phrase, gated behind a secret-warning step. */
@Composable
private fun RevealPhraseDialog(state: AzulaState, onClose: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val phrase = remember { state.exportRecoveryPhrase() }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (revealed) "Your recovery phrase" else "Reveal recovery phrase?") },
        text = {
            when {
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
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AzulaColors.inputBg)
                            .border(1.dp, AzulaColors.border4, RoundedCornerShape(10.dp)).padding(12.dp),
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
            if (!revealed && phrase != null) TextButton(onClick = { revealed = true }) { Text("Reveal") }
            else TextButton(onClick = onClose) { Text("Done") }
        },
        dismissButton = { if (!revealed) TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Paste a recovery phrase to replace this device's identity (destructive). */
@Composable
private fun RestorePhraseDialog(state: AzulaState, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Restore from recovery phrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste your 24-word recovery phrase. This replaces the identity on this device — back up the current one first if you still need it.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
                )
                EditorField("recovery phrase", input, "word word word …") { input = it; error = false }
                if (error) Text("That isn't a valid recovery phrase.", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val ok = state.importRecoveryPhrase(input)
                        working = false
                        if (ok) onClose() else error = true
                    }
                },
            ) { Text(if (working) "Restoring…" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AzulaColors.bg2)
            .border(1.dp, AzulaColors.border2, RoundedCornerShape(12.dp))
            .clickable { onEdit() }
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AvatarBox(bitmap, persona.name.firstOrNull()?.uppercaseChar()?.toString() ?: "•", dev.azula.model.ConversationKind.PEER)
        Column(Modifier.weight(1f)) {
            Text(persona.name, color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                persona.description?.takeIf { it.isNotBlank() } ?: "no description",
                color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.sp,
            )
        }
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (isDefault) AzulaColors.pink else AzulaColors.border4, RoundedCornerShape(6.dp))
                .clickable { onSetDefault() }.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(if (isDefault) "default ✓" else "set default", color = if (isDefault) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 10.sp)
        }
        ConvOverflowMenu(enabled = true, onToggleEnabled = null, onDelete = onDelete)
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
                        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, AzulaColors.border4, RoundedCornerShape(8.dp))
                            .clickable { launchPicker() }.padding(horizontal = 11.dp, vertical = 7.dp),
                    ) {
                        Text(if (avatarB64 == null) "pick picture" else "change picture", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 11.sp)
                    }
                    if (avatarB64 != null) {
                        Text("remove", color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp, modifier = Modifier.clickable { avatarB64 = null; mime = null })
                    }
                }
                EditorField("name", name, "your name") { name = it }
                EditorField("description", description, "a short bio (optional)") { description = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(Persona(id = id, name = name.trim(), description = description.trim().ifBlank { null }, avatarB64 = avatarB64, mime = mime))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun EditorField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 9.sp)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AzulaColors.inputBg)
                .border(1.dp, AzulaColors.border4, RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp),
                cursorBrush = SolidColor(AzulaColors.pink),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 13.sp)
                    inner()
                },
            )
        }
    }
}

/**
 * A "which profile to share" sheet, reused for outbound (connect) and inbound
 * (a peer connected to us). The user picks a persona and toggles fields; the
 * confirm action yields a [Frame.Profile], the dismiss action yields null
 * (caller decides what null means: connect-without-sharing, or decline).
 */
@Composable
fun ShareSheet(
    state: AzulaState,
    title: String,
    subtitle: String,
    confirmLabel: String,
    dismissLabel: String,
    onResult: (Frame.Profile?) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(state.defaultPersonaId ?: state.personas.firstOrNull()?.id) }
    var shareName by remember { mutableStateOf(true) }
    var sharePic by remember { mutableStateOf(true) }
    var shareDesc by remember { mutableStateOf(true) }
    val selected = state.personas.firstOrNull { it.id == selectedId }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subtitle, color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp)
                if (state.personas.isEmpty()) {
                    Text("No personas yet — create one in Settings to share a profile.", color = AzulaColors.textFaint, fontFamily = Fonts.mono, fontSize = 11.5.sp)
                } else {
                    // Persona picker (clickable chips).
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.personas.forEach { p ->
                            val on = p.id == selectedId
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (on) Color(0x1FFF2D9B) else Color.Transparent)
                                    .border(1.dp, if (on) AzulaColors.pink else AzulaColors.border4, RoundedCornerShape(8.dp))
                                    .clickable { selectedId = p.id }.padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(p.name, color = if (on) AzulaColors.pinkLight else AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp)
                            }
                        }
                    }
                    ShareToggle("share name", shareName) { shareName = it }
                    ShareToggle("share picture", sharePic && selected?.avatarB64 != null, enabled = selected?.avatarB64 != null) { sharePic = it }
                    ShareToggle("share description", shareDesc && selected?.description != null, enabled = selected?.description != null) { shareDesc = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let { onResult(it.toFrame(shareName, sharePic, shareDesc)) } },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text(dismissLabel) } },
    )
}

@Composable
private fun ShareToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) AzulaColors.text2 else AzulaColors.textFaint, fontFamily = Fonts.ui, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
