package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.azula.model.MediaKind
import platform.darwin.NSObject

@Composable
actual fun rememberMediaPicker(kind: MediaKind, onPicked: (PickedFile) -> Unit): () -> Unit {
    return remember(kind) {
        var activeDelegate: NSObject? = null
        {
            val onResult: (PickedFile) -> Unit = { picked ->
                activeDelegate = null
                onPicked(picked)
            }
            activeDelegate = when (kind) {
                MediaKind.IMAGE -> presentPhPicker(imagesOnly = true, onResult)
                MediaKind.VIDEO -> presentPhPicker(imagesOnly = false, onResult)
                MediaKind.AUDIO -> presentDocumentPicker(listOf(UTTypeAudioKind), onResult)
                MediaKind.FILE -> presentDocumentPicker(listOf(UTTypeDataKind), onResult)
            }
        }
    }
}
