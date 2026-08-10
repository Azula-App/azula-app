package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android fills through the explicit Credential Manager affordance
 * ([rememberPlatformPhraseFiller]), not through the field, so this is the plain
 * Compose field.
 */
@Composable
actual fun PhraseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier,
) = ComposePhraseTextField(value, onValueChange, placeholder, singleLine, modifier)
