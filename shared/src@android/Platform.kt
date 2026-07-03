package dev.azula

actual fun platformName() = "android"

actual val platformHasSoftKeyboard: Boolean = true

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000
