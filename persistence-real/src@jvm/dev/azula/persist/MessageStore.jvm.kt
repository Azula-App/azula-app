package dev.azula.persist

import java.io.File

actual fun platformMessageStore(): MessageStore? = FileMessageStore(messageStoreDir())

/** `$AZULA_DATA_DIR/.azula` when the isolation override is set (see
 *  [azulaHomeDir]), else `~/.azula` as before. */
internal fun messageStoreDir(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula")
