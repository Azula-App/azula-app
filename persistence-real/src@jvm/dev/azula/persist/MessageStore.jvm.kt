package dev.azula.persist

import java.io.File

actual fun platformMessageStore(): MessageStore? =
    FileMessageStore(File(System.getProperty("user.home") ?: ".", ".azula"))
