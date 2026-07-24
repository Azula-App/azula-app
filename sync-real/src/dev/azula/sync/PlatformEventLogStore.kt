package dev.azula.sync

import dev.azula.core.Ed25519

/**
 * Platform default event-log store, given a real [ed25519] to sign/verify with (the
 * assembler supplies one wired to a real crypto implementation -- e.g.
 * `dev.azula.net.IrohKmpEd25519` from `network-real` -- since this module can only
 * depend on other features' `-api`, never `-real`). JVM and iOS return a real
 * file-backed [FileEventLogStore]/`NsFileManagerEventLogStore`; Android returns
 * `null` here (a real per-app directory needs a `Context` this module never
 * receives) -- see `AndroidEventLogStore()`, the same pattern as
 * `persistence-real`'s `platformMessageStore()`/`AndroidMessageStore()`.
 *
 * [rootPkHex] is the identity's root public key (lowercase hex), which namespaces
 * the on-disk store (multi-device-identity task 4.6: `eventlog/<root_pk_hex>/
 * <device_pk_hex>.jsonl`, fixing a privacy bug where a device's pre-enrollment
 * history would otherwise fold into whatever identity next certified its device
 * key). It's a *lazy* supplier, not a plain `String`, deliberately: this function
 * is called at composition-root time (`AppEntry.kt`/`AzulaApplication.kt`), before
 * the transport has bound and therefore before the root pk is knowable -- but nothing
 * calls this store's methods until after `ConnectService.start()` binds it (see
 * `AccountSyncService`'s `deviceIdentity`, wired the same way, for the identical
 * ordering constraint). [FileEventLogStore]/`NsFileManagerEventLogStore` only invoke
 * [rootPkHex] on first real disk access -- so it's never evaluated until the caller
 * genuinely knows the answer. Threaded through rather than defaulted: a call site
 * with no root pk to hand over yet is a wiring bug that must fail loudly, not a
 * plausible-looking placeholder directory to fall back on silently.
 */
expect fun platformEventLogStore(ed25519: Ed25519, rootPkHex: () -> String): EventLogStore?
