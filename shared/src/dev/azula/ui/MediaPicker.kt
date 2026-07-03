package dev.azula.ui

import androidx.compose.runtime.Composable
import dev.azula.model.MediaKind

/**
 * Returns a lambda that, when invoked, opens the platform's native picker scoped
 * to [kind]: image/video pickers restrict to that media type, audio opens a
 * document picker filtered to audio, and [MediaKind.FILE] falls back to a
 * generic file picker. When the user picks something, [onPicked] is called with
 * the result. Actuals live in src@android, src@jvm, and src@ios. Mirrors
 * [rememberFilePicker]'s contract, and reuses [PickedFile] as the result type —
 * the two shapes (name/mime/bytes) are identical, so a separate `PickedMedia`
 * would just be a rename.
 */
@Composable
expect fun rememberMediaPicker(kind: MediaKind, onPicked: (PickedFile) -> Unit): () -> Unit
