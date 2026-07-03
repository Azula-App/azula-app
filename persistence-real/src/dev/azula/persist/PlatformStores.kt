package dev.azula.persist

/**
 * Platform default stores. JVM writes files; iOS uses NSUserDefaults; Android
 * returns null here and binds a Context-backed store into the Metro graph via the
 * app module. The actuals live in this module's src@jvm / src@android / src@ios.
 */
expect fun platformMessageStore(): MessageStore?

expect fun platformProfileStore(): ProfileStore?

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

/** Turns an arbitrary id into a filesystem-safe filename fragment. Shared by every
 * platform's file-backed store (JVM/Android [FileMessageStore]/[FileBlobStore], and
 * iOS's blob store) so ids are sanitized identically everywhere. */
internal fun sanitize(s: String): String =
    s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
