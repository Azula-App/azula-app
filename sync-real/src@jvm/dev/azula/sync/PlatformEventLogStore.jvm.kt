package dev.azula.sync

import dev.azula.core.Ed25519
import java.io.File

/** Stores the identity's per-device logs under
 *  `~/.azula/eventlog/<root_pk_hex>/<device_pk_hex>.jsonl` (multi-device-identity
 *  task 4.6). */
actual fun platformEventLogStore(ed25519: Ed25519, rootPkHex: () -> String): EventLogStore? =
    FileEventLogStore(File(System.getProperty("user.home") ?: ".", ".azula"), ed25519, rootPkHex)
