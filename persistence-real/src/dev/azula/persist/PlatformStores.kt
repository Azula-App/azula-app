package dev.azula.persist

/**
 * Platform default stores. JVM writes files; iOS uses NSUserDefaults; Android
 * returns null here and binds a Context-backed store into the Metro graph via the
 * app module. The actuals live in this module's src@jvm / src@android / src@ios.
 */
expect fun platformMessageStore(): MessageStore?

expect fun platformProfileStore(): ProfileStore?

/** Platform default settings store (small standalone flags -- see [SettingsStore]).
 *  JVM writes a file; iOS uses NSUserDefaults; Android returns null here and binds
 *  a Context-backed store into the Metro graph via the app module, same as
 *  [platformProfileStore]. */
expect fun platformSettingsStore(): SettingsStore?

/** Platform default invitations store. JVM writes a file; iOS uses NSUserDefaults;
 *  Android returns null here and binds a Context-backed store into the Metro graph
 *  via the app module, same as [platformProfileStore]/[platformMessageStore]. */
expect fun platformInvitationsStore(): InvitationsStore?

/**
 * Platform default blob store (media file bytes). JVM and Android both write
 * flat files (via [FileBlobStore]); iOS writes flat files too, under
 * NSDocumentDirectory (see `BlobStore.ios.kt`) — the first on-disk file store on
 * iOS, since [MessageStore]/[ProfileStore] there use NSUserDefaults, which isn't
 * suited to media-sized blobs. Android returns null here and binds a
 * Context-backed store into the Metro graph via the app module, same as
 * [platformMessageStore].
 */
expect fun platformBlobStore(): BlobStore?

/**
 * Platform default media exporter (auto-save received media to user-visible
 * storage). JVM copies into `~/Downloads/Azula/`; iOS saves images/video to the
 * Photos library and other files into `Documents/Azula/` (Files.app). Android
 * returns null here and binds an `AndroidMediaExporter(context)` into the Metro
 * graph via the app module, same as [platformBlobStore]. Null means "no export"
 * (e.g. the `-mock` apps), and callers treat export as best-effort.
 */
expect fun platformMediaExporter(): MediaExporter?

/** Turns an arbitrary id into a filesystem-safe filename fragment. Shared by every
 * platform's file-backed store (JVM/Android [FileMessageStore]/[FileBlobStore], and
 * iOS's blob store) so ids are sanitized identically everywhere. */
internal fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
