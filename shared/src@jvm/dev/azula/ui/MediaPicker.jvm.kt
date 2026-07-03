package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.azula.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.io.File
import java.io.FilenameFilter

private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic")
private val videoExtensions = setOf("mp4", "mov", "webm", "mkv")
private val audioExtensions = setOf("mp3", "m4a", "wav", "ogg", "flac")

/** Extensions to filter [FileDialog] to for a given [MediaKind]; null (FILE) means no filter. */
private fun extensionsFor(kind: MediaKind): Set<String>? = when (kind) {
    MediaKind.IMAGE -> imageExtensions
    MediaKind.VIDEO -> videoExtensions
    MediaKind.AUDIO -> audioExtensions
    MediaKind.FILE -> null
}

@Composable
actual fun rememberMediaPicker(kind: MediaKind, onPicked: (PickedFile) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(kind) {
        {
            scope.launch(Dispatchers.IO) {
                val dialog = FileDialog(null as java.awt.Frame?, "Choose ${kind.name.lowercase()}", FileDialog.LOAD)
                // AWT's FileDialog has no MIME-based filtering API, only a FilenameFilter
                // callback over each candidate's name — approximate with an extension list.
                extensionsFor(kind)?.let { extensions ->
                    dialog.filenameFilter = FilenameFilter { _, name ->
                        extensions.any { name.lowercase().endsWith(".$it") }
                    }
                }
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
