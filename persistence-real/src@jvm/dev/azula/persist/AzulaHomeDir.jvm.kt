package dev.azula.persist

/**
 * The effective home directory for azula desktop's on-disk state: the
 * `AZULA_DATA_DIR` override when set, otherwise the real `user.home` exactly
 * as before -- mirrors network-real's identically-named/documented helper
 * (`IrohFfiTransport.kt`); duplicated here because persistence-real is a
 * separate Amper module with no shared internal boundary. See
 * `azula-docs/openspec/specs/testing/design.md` for the full rationale.
 *
 * Shared by every `*File()`/`*Dir()` helper in this package so a developer can
 * point a second, fully isolated desktop instance's settings/profiles/blobs/
 * messages/invitations/exports at a scratch directory without touching the
 * primary install's `~/.azula`. Unset (the default for every real user)
 * resolves identically to before this override existed.
 *
 * `env` is overridable so tests can exercise both branches deterministically
 * without mutating the real process environment (the JVM has no supported way
 * to set an env var for a running process).
 */
internal fun azulaHomeDir(env: (String) -> String? = System::getenv): String =
    env("AZULA_DATA_DIR")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") ?: ".")
