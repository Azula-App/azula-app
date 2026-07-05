package dev.azula.persist

/**
 * Copies a received media/file blob out of the app's private [BlobStore] into
 * user-visible device storage, so the user can re-view or share it outside Azula:
 * images/video into the platform gallery, everything else into a Downloads/Files
 * location. Auto-save must be prompt-free (no interactive picker); the only
 * exception is a one-time OS permission grant (e.g. iOS add-to-Photos), which is
 * a system prompt, not an app one.
 *
 * Deliberately mime-string based (not [dev.azula.model.MediaKind]) so callers in
 * `shared` need only a mime; the platform impl classifies image/video/audio/other
 * itself. Implementations live in `persistence-real` (+ the Android app module,
 * which supplies the `Context`).
 */
interface MediaExporter {
    /**
     * Copy the file at [path] into user-visible storage under [name] (a
     * display filename) with the given [mime]. Returns a short human-readable
     * display location (e.g. "Photos", "Downloads/foo.pdf") on success, or null
     * on any failure — callers treat export as best-effort and never surface a
     * hard error for it.
     */
    suspend fun export(path: String, name: String, mime: String): String?
}
