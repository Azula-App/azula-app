package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

@Composable
actual fun rememberAudioPlayer(path: String): AudioPlayerHandle {
    val handle = remember(path) { JvmAudioPlayerHandle(path) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    LaunchedEffect(handle.isPlaying) {
        while (handle.isPlaying) {
            handle.refresh()
            delay(200)
        }
    }
    return handle
}

/**
 * WAV-only inline playback via [javax.sound.sampled.Clip] — AWT/Swing has no
 * built-in decoder for mp3/aac/etc. Anything else leaves [clip] null, so
 * [supported] is false and the UI falls back to [openExternally].
 */
private class JvmAudioPlayerHandle(private val path: String) : AudioPlayerHandle {
    private val clip: Clip? = if (path.endsWith(".wav", ignoreCase = true)) {
        runCatching {
            AudioSystem.getClip().also { c ->
                AudioSystem.getAudioInputStream(File(path)).use { c.open(it) }
                c.addLineListener { event -> if (event.type == LineEvent.Type.STOP) isPlaying = false }
            }
        }.getOrNull()
    } else {
        null
    }

    override var isPlaying by mutableStateOf(false)
        private set
    override var positionMs by mutableStateOf(0L)
        private set
    override val durationMs: Long = clip?.let { it.microsecondLength / 1000 } ?: 0L
    override val supported: Boolean = clip != null

    fun refresh() { positionMs = (clip?.microsecondPosition ?: 0L) / 1000 }

    override fun toggle() {
        val c = clip ?: return
        if (c.isRunning) {
            c.stop()
            isPlaying = false
        } else {
            if (c.microsecondPosition >= c.microsecondLength) c.microsecondPosition = 0
            c.start()
            isPlaying = true
        }
    }

    override fun openExternally() {
        runCatching { Desktop.getDesktop().open(File(path)) }
    }

    fun release() = runCatching { clip?.close() }
}
