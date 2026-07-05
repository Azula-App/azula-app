@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL

@Composable
actual fun rememberAudioPlayer(path: String): AudioPlayerHandle {
    val handle = remember(path) { IosAudioPlayerHandle(path) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    LaunchedEffect(handle.isPlaying) {
        while (handle.isPlaying) {
            handle.refresh()
            delay(200)
        }
    }
    return handle
}

private class IosAudioPlayerHandle(path: String) : AudioPlayerHandle {
    private val player = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)

    override var isPlaying by mutableStateOf(false)
        private set
    override var positionMs by mutableStateOf(0L)
        private set
    override val durationMs: Long = ((player?.duration ?: 0.0) * 1000).toLong()
    override val supported: Boolean = player != null

    fun refresh() { positionMs = ((player?.currentTime ?: 0.0) * 1000).toLong() }

    override fun toggle() {
        val p = player ?: return
        if (p.playing) {
            p.pause()
            isPlaying = false
        } else {
            p.play()
            isPlaying = true
        }
    }

    override fun seekTo(ms: Long) {
        val p = player ?: return
        p.currentTime = ms / 1000.0
        positionMs = ms
    }

    fun release() { player?.stop() }
}
