package dev.azula.net

import dev.azula.core.Ed25519

/** Each platform supplies its concrete iroh-backed [IrohTransport]. */
expect fun createTransport(): IrohTransport

/**
 * The real [Ed25519] wired to iroh-kmp's free functions -- each platform's own
 * `IrohKmpEd25519` object (duplicated per source set rather than shared: see that
 * object's kdoc in `IrohFfiTransport.kt`/`IrohTransport.ios.kt` for why). Exposed via
 * an expect/actual, mirroring [createTransport], so common code (the `AppGraph`
 * composition roots in `shared`) can wire it into `sync-api`'s `EventLogStore`/
 * `AccountSyncFold` without depending on iroh-kmp itself -- multi-device-identity
 * task 4.5's signing identity for this device's own event-log entries.
 */
expect fun platformEd25519(): Ed25519
