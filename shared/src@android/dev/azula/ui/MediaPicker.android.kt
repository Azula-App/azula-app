package dev.azula.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.azula.model.MediaKind

@Composable
actual fun rememberMediaPicker(kind: MediaKind, onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val mimeType = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
        MediaKind.FILE -> "*/*"
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        resolvePickedFile(context, uri)?.let(onPicked)
    }
    return { launcher.launch(mimeType) }
}
