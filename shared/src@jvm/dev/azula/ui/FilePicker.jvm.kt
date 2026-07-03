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
                        onPicked(PickedFile(name = file, mime = resolveMime(f), bytes = bytes))
                    }
                }
            }
            Unit
        }
    }
}

/**
 * Extension-to-MIME fallbacks for formats [Files.probeContentType] often misses
 * (it depends on the OS's registered file-type map, which is spotty for newer
 * image/audio/video formats). Shared by [rememberFilePicker] and
 * `rememberMediaPicker` (MediaPicker.jvm.kt).
 */
private val extensionMimeFallbacks = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "heic" to "image/heic",
    "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mkv" to "video/x-matroska",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg", "flac" to "audio/flac",
)

internal fun resolveMime(file: File): String =
    runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
        ?: extensionMimeFallbacks[file.extension.lowercase()]
        ?: "application/octet-stream"
