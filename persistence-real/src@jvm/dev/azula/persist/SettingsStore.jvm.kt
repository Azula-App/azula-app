package dev.azula.persist

import java.io.File

actual fun platformSettingsStore(): SettingsStore? = RawSettingsStore(
    readRaw = { runCatching { settingsFile().takeIf { it.exists() }?.readText() }.getOrNull() },
    writeRaw = { text ->
        runCatching {
            val f = settingsFile()
            f.parentFile?.mkdirs()
            f.writeText(text)
        }
    },
)

private fun settingsFile() = File(System.getProperty("user.home"), ".azula/settings.json")
