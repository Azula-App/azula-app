package dev.azula.sync

import android.content.Context
import dev.azula.core.Ed25519

// Android needs a Context, so the default is null; a future caller builds the
// AppGraph with AndroidEventLogStore(applicationContext, ed25519, rootPkHex) instead
// -- same pattern as persistence-real's platformMessageStore()/AndroidMessageStore().
actual fun platformEventLogStore(ed25519: Ed25519, rootPkHex: () -> String): EventLogStore? = null

/** Stores the identity's per-device logs under
 *  `filesDir/eventlog/<root_pk_hex>/<device_pk_hex>.jsonl` (multi-device-identity
 *  task 4.6) -- see [platformEventLogStore]'s kdoc for why [rootPkHex] is a lazy
 *  supplier rather than a plain `String`. */
fun AndroidEventLogStore(context: Context, ed25519: Ed25519, rootPkHex: () -> String): EventLogStore =
    FileEventLogStore(context.filesDir, ed25519, rootPkHex)
