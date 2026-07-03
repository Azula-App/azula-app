package dev.azula.persist

import java.io.File

actual fun platformProfileStore(): ProfileStore? = RawProfileStore(
    readRaw = { runCatching { profileFile().takeIf { it.exists() }?.readText() }.getOrNull() },
    writeRaw = { text ->
        runCatching {
            val f = profileFile()
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    },
)

private fun profileFile() = File(System.getProperty("user.home"), ".azula/profiles.json")
