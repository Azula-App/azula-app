package dev.azula.ui

import java.io.File

// TODO Phase 1c: this has no Context (ui-common's audio seam is Context-free by
// design), so it can't target `context.cacheDir` -- it falls back to the JVM
// temp dir (`java.io.tmpdir`), which is writable and app-private on current
// Android (it resolves under the app's own storage), but isn't the dedicated
// cache directory the OS can reclaim under storage pressure. Revisit if a
// Context becomes available to this seam.
actual fun cacheAudioFile(bytes: ByteArray, key: String): String? = runCatching {
    val dir = File(System.getProperty("java.io.tmpdir"), "azula-audio-cache").apply { mkdirs() }
    val safeKey = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    File(dir, "$safeKey.cache").apply { writeBytes(bytes) }.absolutePath
}.getOrNull()
