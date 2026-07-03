package dev.azula

actual fun platformName() = "desktop"

actual val platformHasSoftKeyboard: Boolean = false

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000
