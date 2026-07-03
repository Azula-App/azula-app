package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.darwin.NSObject

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    return remember {
        var activeDelegate: NSObject? = null
        {
            activeDelegate = presentDocumentPicker(listOf(UTTypeDataKind)) { picked ->
                activeDelegate = null
                onPicked(picked)
            }
        }
    }
}
