package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop has no credential store to fill from, so the phrase field is the plain
 * Compose field -- typing and pasting, exactly as before. This is also the actual
 * the JVM UI tests exercise (`SetupFlowE2eTest`), which is why the fill seam is
 * [LocalPhraseFiller] rather than anything field-level.
 */
@Composable
actual fun PhraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier,
) = ComposePhraseTextField(value, onValueChange, placeholder, singleLine, modifier)
