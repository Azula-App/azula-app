package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Desktop has no system credential store to hand a secret to and no share sheet,
 * so it reports [PhraseSaveResult.UNAVAILABLE] and the caller falls back to the
 * clipboard copy -- the behavior desktop has always had here.
 */
@Composable
actual fun rememberPlatformPhraseSaver(): (String, (PhraseSaveResult) -> Unit) -> Unit = remember {
    { _, onResult -> onResult(PhraseSaveResult.UNAVAILABLE) }
}
