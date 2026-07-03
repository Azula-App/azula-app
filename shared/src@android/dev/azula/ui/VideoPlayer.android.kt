package dev.azula.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

@Composable
actual fun VideoPlayer(path: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setVideoURI(Uri.fromFile(File(path)))
                setOnPreparedListener { it.start() }
            }
        },
    )
}
