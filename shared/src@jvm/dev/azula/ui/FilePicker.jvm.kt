package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.io.File
import java.nio.file.Files

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch(Dispatchers.IO) {
                val dialog = FileDialog(null as java.awt.Frame?, "Choose a file", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    runCatching {
                        val f = File(dir, file)
                        val bytes = f.readBytes()
                        val mime = runCatching { Files.probeContentType(f.toPath()) }.getOrNull()
                            ?: "application/octet-stream"
                        onPicked(PickedFile(name = file, mime = mime, bytes = bytes))
                    }
                }
            }
            Unit
        }
    }
}
