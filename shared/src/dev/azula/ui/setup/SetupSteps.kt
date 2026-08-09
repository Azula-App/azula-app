package dev.azula.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.model.ConversationKind
import dev.azula.persist.Persona
import dev.azula.state.AzulaState
import dev.azula.theme.AzulaButtonVariant
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaButtonSurface
import dev.azula.theme.azulaCard
import dev.azula.theme.azulaGlowHalo
import dev.azula.theme.azulaInputSurface
import dev.azula.theme.azulaMutedPanel
import dev.azula.ui.AvatarBox
import dev.azula.ui.ComposePhraseTextField
import dev.azula.ui.PhraseFieldBoxMinHeight
import dev.azula.ui.PhraseFieldBoxPadding
import dev.azula.ui.PhraseFillResult
import dev.azula.ui.PhraseSaveResult
import dev.azula.ui.PhraseTextField
import dev.azula.ui.decodeImageBitmap
import dev.azula.ui.rememberFilePicker
import dev.azula.ui.rememberPhraseFiller
import dev.azula.ui.rememberPhraseSaver
import dev.azula.ui.scaleImageForAvatar
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Step content shared by both [SetupWideShell]/[SetupCompactShell] -- only the
 * chrome around these differs between the two presentations (design.md D4).
 */

// ---------------------------------------------------------------------------
// FORK -- "create a new identity" (recommended) vs "I have a recovery phrase"
// ---------------------------------------------------------------------------

@Composable
internal fun ForkStepContent(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("set up your identity", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "azula gives this device its own key, backed up by a 24-word recovery phrase. choose how to start.",
                color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        Box(
            Modifier.fillMaxWidth().azulaGlowHalo()
                .clickable { onCreate() }
                .padding(20.dp)
                .testTag("setup-fork-create"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.clip(AzulaShapes.rPill).background(AzulaColors.primarySelected)
                        .border(1.dp, AzulaColors.primaryEdgeStrong, AzulaShapes.rPill)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text("RECOMMENDED", color = AzulaColors.pinkLight, fontFamily = Fonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                }
                Text("Create a new identity", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "generate a fresh device key, then back it up with a recovery phrase.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Box(Modifier.azulaButtonSurface(AzulaButtonVariant.Primary).padding(horizontal = 16.dp, vertical = 9.dp)) {
                    Text("Get started ›", color = Color.White, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().azulaCard()
                .clickable { onRestore() }
                .padding(20.dp)
                .testTag("setup-fork-restore"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("I have a recovery phrase", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "restore an existing identity from its 24-word recovery phrase.",
                    color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// RESTORE -- reached only from FORK; success skips BACKUP entirely
// ---------------------------------------------------------------------------

@Composable
internal fun RestoreStepContent(state: AzulaState, onRestored: () -> Unit, onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Null where the platform has no explicit fill flow: iOS offers the phrase
    // through the native field's AutoFill instead, and desktop has no credential
    // store at all. Both render no button rather than one that can only say "no".
    val fillPhrase = rememberPhraseFiller()
    // Only ever holds Unavailable long enough to report it. Filled needs no label --
    // the phrase visibly lands in the field -- and Cancelled is the user declining,
    // which has nothing to say back. Same 1400 ms flash the back-up step uses.
    var nothingSaved by remember { mutableStateOf(false) }
    LaunchedEffect(nothingSaved) {
        if (nothingSaved) { delay(1400); nothingSaved = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "‹ back",
            color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp,
            modifier = Modifier.clickable { onBack() },
        )
        Text("restore from a recovery phrase", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "paste your 24-word recovery phrase. this replaces the identity just created for this device.",
            color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
        )
        SetupField(
            label = "recovery phrase",
            value = input,
            placeholder = "word word word …",
            tag = "setup-restore-input",
            singleLine = false,
            credentialFill = true,
            onChange = { input = it; error = false },
        )
        if (fillPhrase != null) {
            SetupLinkButton(
                // Reported, not hidden: the affordance can't know the vault is empty
                // until it asks, and silently doing nothing would be indistinguishable
                // from a broken button -- the same reasoning that chose an explicit
                // request over ambient autofill (change design.md).
                label = if (nothingSaved) "no saved phrase found" else "fill from password manager",
                tag = "setup-restore-fill",
                onClick = {
                    fillPhrase { result ->
                        when (result) {
                            // Into `input` only. Restoring replaces this device's identity
                            // key, so it stays behind the Restore button and the checksum
                            // validation below -- a provider handing back a truncated or
                            // wrong phrase must surface as the inline error, not as a
                            // silently replaced identity.
                            is PhraseFillResult.Filled -> { input = result.phrase; error = false }
                            PhraseFillResult.Unavailable -> nothingSaved = true
                            // Dismissing the sheet leaves the input exactly as it was.
                            PhraseFillResult.Cancelled -> Unit
                        }
                    }
                },
            )
        }
        if (error) {
            Text(
                "that isn't a valid recovery phrase.",
                color = AzulaColors.error, fontFamily = Fonts.mono, fontSize = 11.sp,
                modifier = Modifier.testTag("setup-restore-error"),
            )
        }
        SetupPrimaryButton(
            label = if (working) "Restoring…" else "Restore",
            tag = "setup-restore-submit",
            enabled = input.isNotBlank() && !working,
        ) {
            working = true
            scope.launch {
                val ok = state.importRecoveryPhrase(input)
                working = false
                if (ok) onRestored() else error = true
            }
        }
    }
}

// ---------------------------------------------------------------------------
// BACKUP -- numbered word grid, copy / save actions, confirm-gated Continue
// ---------------------------------------------------------------------------

@Composable
internal fun BackupStepContent(
    state: AzulaState,
    columns: Int,
    onContinue: () -> Unit,
    onDeferred: () -> Unit,
) {
    val phrase = state.exportRecoveryPhrase()
    val clipboard = LocalClipboardManager.current
    var confirmed by remember { mutableStateOf(false) }
    // The save button's own feedback. Deliberately not `state.copied` (which the
    // copy button uses): that's a single app-wide flash shared with Settings,
    // Connect, Sidebar and the invite sheet, so hanging both buttons off it made
    // tapping "copy" light up "✓ saved" too. Cleared on the same 1400 ms cadence
    // `markCopied()` uses.
    var saveResult by remember { mutableStateOf<PhraseSaveResult?>(null) }
    LaunchedEffect(saveResult) {
        if (saveResult != null) { delay(1400); saveResult = null }
    }
    val savePhrase = rememberPhraseSaver()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("back up your recovery phrase", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "these 24 words are your identity key. anyone who has them can take over your identity — store them only in a password manager.",
            color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
        )
        if (phrase == null) {
            // Bind still in flight (or offline) -- exportRecoveryPhrase() is null until
            // the transport binds. Nothing is fabricated; "back up later" below stays
            // available regardless (spec: Recovery-Phrase Back-Up Step).
            Box(Modifier.fillMaxWidth().azulaMutedPanel().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("your key is being created…", color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 12.sp)
            }
        } else {
            val words = remember(phrase) { phrase.trim().split(" ").filter { it.isNotBlank() } }
            WordGrid(words, columns)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SetupLinkButton(
                    label = if (state.copied) "✓ copied" else "copy to clipboard",
                    tag = "setup-backup-copy",
                    onClick = { clipboard.setText(AnnotatedString(phrase)); state.markCopied() },
                )
                SetupLinkButton(
                    label = when (saveResult) {
                        PhraseSaveResult.SAVED -> "✓ saved"
                        // Says "copied", not "saved" -- the phrase went to the
                        // clipboard, and claiming it reached a vault would leave the
                        // user believing the identity key is backed up when it isn't.
                        PhraseSaveResult.UNAVAILABLE -> "✓ copied instead"
                        // CANCELLED falls through to the idle label: the user dismissed
                        // the sheet, so there's nothing to report.
                        else -> "save to password manager"
                    },
                    tag = "setup-backup-save",
                    onClick = {
                        savePhrase(phrase) { result ->
                            // Only the no-provider/error case falls back to the
                            // clipboard. A user who dismissed the sheet declined to
                            // store the phrase; copying it anyway would put the
                            // identity key somewhere they didn't ask for.
                            if (result == PhraseSaveResult.UNAVAILABLE) {
                                clipboard.setText(AnnotatedString(phrase))
                            }
                            saveResult = result
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it },
                    modifier = Modifier.testTag("setup-backup-confirm"),
                )
                Text("I've saved my recovery phrase", color = AzulaColors.text2, fontFamily = Fonts.ui, fontSize = 13.sp)
            }
            SetupPrimaryButton(label = "Continue", tag = "setup-backup-continue", enabled = confirmed, onClick = onContinue)
        }
        Text(
            "back up later",
            color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp,
            modifier = Modifier.clickable { state.deferBackup(); onDeferred() }.testTag("setup-backup-later"),
        )
    }
}

@Composable
private fun WordGrid(words: List<String>, columns: Int) {
    Column(
        Modifier.fillMaxWidth().azulaInputSurface(AzulaShapes.rMd).padding(14.dp).testTag("setup-backup-phrase"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        words.chunked(columns).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { colIdx, word ->
                    val number = rowIdx * columns + colIdx + 1
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(twoDigits(number), color = AzulaColors.textFaint2, fontFamily = Fonts.mono, fontSize = 10.sp)
                        Text(word, color = AzulaColors.text1, fontFamily = Fonts.mono, fontSize = 13.sp)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun twoDigits(n: Int): String = if (n < 10) "0$n" else n.toString()

// ---------------------------------------------------------------------------
// PERSONA -- first-run-slimmed persona form; skip writes nothing
// ---------------------------------------------------------------------------

@OptIn(ExperimentalEncodingApi::class)
private fun encodeSetupAvatar(bytes: ByteArray): Pair<String, String>? =
    scaleImageForAvatar(bytes)?.let { Base64.encode(it.bytes) to it.mime }

@OptIn(ExperimentalEncodingApi::class)
private fun setupAvatarBitmap(b64: String?) =
    b64?.let { runCatching { decodeImageBitmap(Base64.decode(it)) }.getOrNull() }

@Composable
internal fun PersonaStepContent(state: AzulaState) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatarB64 by remember { mutableStateOf<String?>(null) }
    var mime by remember { mutableStateOf<String?>(null) }
    val preview = remember(avatarB64) { setupAvatarBitmap(avatarB64) }
    val launchPicker = rememberFilePicker { picked ->
        if (picked.mime.startsWith("image/")) {
            encodeSetupAvatar(picked.bytes)?.let { (b64, m) -> avatarB64 = b64; mime = m }
        }
    }
    fun save() {
        state.upsertPersona(
            Persona(
                id = state.newPersonaId(),
                name = name.trim(),
                description = description.trim().ifBlank { null },
                avatarB64 = avatarB64,
                mime = mime,
            ),
        )
        state.completeSetup()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("set up your profile", color = AzulaColors.text0, fontFamily = Fonts.ui, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            "this is what peers see when you connect. add more personas anytime in settings.",
            color = AzulaColors.textDim2, fontFamily = Fonts.ui, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AvatarBox(preview, name.firstOrNull()?.uppercaseChar()?.toString() ?: "•", ConversationKind.PEER, size = 56.dp)
            Box(
                Modifier.clip(AzulaShapes.rSm).border(1.dp, AzulaColors.border4, AzulaShapes.rSm)
                    .clickable { launchPicker() }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (avatarB64 == null) "pick picture" else "change picture", color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 11.sp)
            }
        }
        SetupField(label = "name", value = name, placeholder = "your name", tag = "setup-persona-name", onChange = { name = it })
        SetupField(label = "description", value = description, placeholder = "a short bio (optional)", tag = "setup-persona-desc", onChange = { description = it })
        SetupPrimaryButton(label = "Save", tag = "setup-persona-save", enabled = name.isNotBlank()) { save() }
        Text(
            "skip for now",
            color = AzulaColors.textDim, fontFamily = Fonts.mono, fontSize = 12.sp,
            modifier = Modifier.clickable { state.completeSetup() }.testTag("setup-persona-skip"),
        )
    }
}

// ---------------------------------------------------------------------------
// Local shared primitives (mirroring Settings.kt's private EditorField, which
// isn't importable -- see the task brief).
// ---------------------------------------------------------------------------

@Composable
private fun SetupField(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    singleLine: Boolean = true,
    // Opts this one field into the platform's credential-fill input. Only the
    // recovery-phrase field sets it: on iOS that swaps the Compose field for a
    // native UITextField so AutoFill can see it, which is interop the name and
    // avatar fields have no reason to carry.
    credentialFill: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 9.sp)
        Box(
            Modifier.fillMaxWidth()
                .let { if (!singleLine) it.heightIn(min = PhraseFieldBoxMinHeight) else it }
                .azulaInputSurface(AzulaShapes.rSm)
                .padding(horizontal = 11.dp, vertical = PhraseFieldBoxPadding),
        ) {
            val fieldModifier = Modifier.fillMaxWidth().testTag(tag)
            if (credentialFill) {
                PhraseTextField(value, onChange, placeholder, singleLine, fieldModifier)
            } else {
                ComposePhraseTextField(value, onChange, placeholder, singleLine, fieldModifier)
            }
        }
    }
}

/** The flow's primary CTA (Restore / Continue / Save) -- gradient+glow fill,
 *  dimmed and non-interactive while [enabled] is false. */
@Composable
private fun SetupPrimaryButton(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            // `alpha` is a graphicsLayer: it only dims the content and the
            // modifiers to its right, so it has to lead the chain to reach the
            // fill and glow. Behind `azulaButtonSurface` it faded the label
            // alone and left a full-strength CTA looking enabled.
            .alpha(if (enabled) 1f else 0.45f)
            .azulaButtonSurface(AzulaButtonVariant.Primary)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** A lower-emphasis action (copy / save to password manager). */
@Composable
private fun SetupLinkButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.azulaInputSurface(AzulaShapes.rSm)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
    ) {
        Text(label, color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}
