package dev.azula

actual fun platformName() = "android"

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000
