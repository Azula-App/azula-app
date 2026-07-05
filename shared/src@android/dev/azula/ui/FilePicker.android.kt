package dev.azula.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        resolvePickedFile(context, uri)?.let(onPicked)
    }
    return { launcher.launch("*/*") }
}

/**
 * Reads bytes/mime/display-name for a content [uri] via the [ContentResolver]. Shared
 * by [rememberFilePicker] and `rememberMediaPicker` (MediaPicker.android.kt) — both
 * launch [ActivityResultContracts.GetContent] and need the same resolution. Public
 * (rather than `internal`) so `android-app`'s `MainActivity` can resolve
 * `ACTION_SEND`/`ACTION_SEND_MULTIPLE` share-sheet Uris the same way.
 */
fun resolvePickedFile(context: Context, uri: Uri): PickedFile? {
    val cr = context.contentResolver
    val bytes = runCatching { cr.openInputStream(uri)!!.readBytes() }.getOrNull() ?: return null
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val name: String = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
    return PickedFile(name = name, mime = mime, bytes = bytes)
}
