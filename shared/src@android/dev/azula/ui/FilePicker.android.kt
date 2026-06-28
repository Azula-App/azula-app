package dev.azula.ui

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
        val cr = context.contentResolver
        val bytes = runCatching { cr.openInputStream(uri)!!.readBytes() }.getOrNull() ?: return@rememberLauncherForActivityResult
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name: String = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        onPicked(PickedFile(name = name, mime = mime, bytes = bytes))
    }
    return { launcher.launch("*/*") }
}
