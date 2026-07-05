@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.azula.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.*

actual fun cacheAudioFile(bytes: ByteArray, key: String): String? = runCatching {
    val safeKey = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val path = NSTemporaryDirectory() + "azula-audio-$safeKey-" + NSUUID().UUIDString + ".cache"
    val data = if (bytes.isEmpty()) NSData() else memScoped {
        NSData.create(bytes = allocArrayOf(bytes), length = bytes.size.toULong())
    }
    if (data.writeToFile(path, atomically = true)) path else null
}.getOrNull()
