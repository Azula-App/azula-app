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

/** `$AZULA_DATA_DIR/.azula/profiles.json` when the isolation override is set
 *  (see [azulaHomeDir]), else `~/.azula/profiles.json` as before. */
internal fun profileFile(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula/profiles.json")
