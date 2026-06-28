package dev.azula.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    // TODO: present UIDocumentPickerViewController via interop to support file picking on iOS.
    return {}
}
