package dev.azula.ui

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
actual fun rememberAudioPlayer(path: String): AudioPlayerHandle {
    val handle = remember(path) { AndroidAudioPlayerHandle(path) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    LaunchedEffect(handle.isPlaying) {
        while (handle.isPlaying) {
            handle.refresh()
            delay(200)
        }
    }
    return handle
}

private class AndroidAudioPlayerHandle(path: String) : AudioPlayerHandle {
    private val player = MediaPlayer().apply {
        setDataSource(path)
        prepare()
        setOnCompletionListener { this@AndroidAudioPlayerHandle.isPlaying = false }
    }

    override var isPlaying by mutableStateOf(false)
        private set
    override var positionMs by mutableStateOf(0L)
        private set
    override val durationMs: Long = player.duration.toLong()
    override val supported = true

    fun refresh() { positionMs = player.currentPosition.toLong() }

    override fun toggle() {
        if (player.isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            player.start()
            isPlaying = true
        }
    }

    override fun seekTo(ms: Long) {
        player.seekTo(ms.toInt())
        positionMs = ms
    }

    fun release() = runCatching { player.release() }
}
