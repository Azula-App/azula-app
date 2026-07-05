package dev.azula.ui

import java.io.File

actual fun cacheAudioFile(bytes: ByteArray, key: String): String? = runCatching {
    val dir = File(System.getProperty("java.io.tmpdir"), "azula-audio-cache").apply { mkdirs() }
    val safeKey = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    File(dir, "$safeKey.cache").apply { writeBytes(bytes) }.absolutePath
}.getOrNull()
