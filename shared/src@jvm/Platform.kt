package dev.azula

actual fun platformName() = "desktop"

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000
