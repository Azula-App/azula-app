package dev.azula.sync

import dev.azula.core.Ed25519
import java.io.File

/**
 * The effective home directory for azula desktop's on-disk state: the
 * `AZULA_DATA_DIR` override when set, otherwise the real `user.home` exactly
 * as before -- mirrors network-real's identically-named/documented helper
 * (`IrohFfiTransport.kt`); duplicated here because sync-real is a separate
 * Amper module with no shared internal boundary. See
 * `azula-docs/openspec/specs/testing/design.md` for the full rationale.
 */
internal fun azulaHomeDir(env: (String) -> String? = System::getenv): String =
    env("AZULA_DATA_DIR")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") ?: ".")

/** Stores the identity's per-device logs under
 *  `~/.azula/eventlog/<root_pk_hex>/<device_pk_hex>.jsonl` (multi-device-identity
 *  task 4.6), or under `$AZULA_DATA_DIR/.azula/eventlog/...` when the isolation
 *  override is set. */
actual fun platformEventLogStore(ed25519: Ed25519, rootPkHex: () -> String): EventLogStore? =
    FileEventLogStore(File(azulaHomeDir(), ".azula"), ed25519, rootPkHex)
