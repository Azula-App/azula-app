package dev.azula.ui

import androidx.compose.runtime.Composable

/** A file chosen by the user via the platform's native file picker. */
data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Returns a lambda that, when invoked, opens the platform file picker.
 * When the user picks a file, [onPicked] is called with the result.
 * Actuals live in src@android, src@jvm, and src@ios.
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
