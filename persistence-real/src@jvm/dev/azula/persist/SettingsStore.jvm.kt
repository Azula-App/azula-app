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

/** `$AZULA_DATA_DIR/.azula/settings.json` when the isolation override is set
 *  (see [azulaHomeDir]), else `~/.azula/settings.json` as before. */
internal fun settingsFile(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula/settings.json")
