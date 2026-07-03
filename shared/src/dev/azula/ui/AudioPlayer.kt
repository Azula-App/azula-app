package dev.azula.ui

import androidx.compose.runtime.Composable

/** Playback state + controls for [rememberAudioPlayer]. */
interface AudioPlayerHandle {
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long

    /** False when this platform/format combination can't play [rememberAudioPlayer]'s
     *  file inline (JVM + anything but WAV) — the caller should show
     *  [openExternally] as a button instead of a progress bar. */
    val supported: Boolean

    fun toggle()

    /** Open the file in the system's default player. A no-op on platforms where
     *  [supported] is always true (Android/iOS never need it); JVM's fallback when
     *  [supported] is false. */
    fun openExternally() {}
}

/**
 * Plays a local audio file at [path] (a BlobStore-native path, like [VideoPlayer]'s)
 * and exposes play/pause + position/duration for an inline progress bar. Actuals:
 * Android `MediaPlayer`, iOS `AVAudioPlayer`, JVM `javax.sound.sampled` (WAV only —
 * anything else reports [AudioPlayerHandle.supported] = false).
 */
@Composable
expect fun rememberAudioPlayer(path: String): AudioPlayerHandle
