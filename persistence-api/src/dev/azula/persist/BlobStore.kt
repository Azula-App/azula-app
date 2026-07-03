package dev.azula.persist

/**
 * Persists blob bodies (media file bytes: images, audio, video) as a flat,
 * id-addressed byte store — separate from [MessageStore], which only holds
 * conversation metadata/text. A blob is written incrementally as it's fetched
 * from a peer (see the media-fetch protocol in `network-api`), so writes always
 * append from the current on-disk size: an interrupted transfer resumes by
 * re-opening a writer, not by tracking separate resume state. Mirrors the
 * [MessageStore] expect/actual + injectable-config pattern.
 */
interface BlobStore {
    /** Whether any bytes have been written for [id]. */
    fun exists(id: String): Boolean

    /** Bytes written so far for [id] (0 if absent). Doubles as the writer's resume offset. */
    fun sizeOnDisk(id: String): Long

    /**
     * Opens a writer that appends starting at [sizeOnDisk], creating the blob if it
     * doesn't exist yet. Caller must [BlobWriter.close] when done.
     */
    fun openWriter(id: String): BlobWriter

    /**
     * Opens a reader starting [offset] bytes into the blob. Caller must
     * [BlobReader.close] when done.
     */
    fun openReader(id: String, offset: Long = 0): BlobReader

    /**
     * A platform-native filesystem path to [id]'s bytes, for handing off to an
     * external viewer/player. Null if the blob is incomplete, absent, or the
     * platform has no such path.
     */
    fun pathFor(id: String): String?

    /** Removes all bytes stored for [id]. Safe to call on a missing id. */
    fun delete(id: String)
}

/** A handle for appending bytes to a blob, opened via [BlobStore.openWriter]. */
interface BlobWriter : AutoCloseable {
    fun write(chunk: ByteArray)
}

/** A handle for reading bytes from a blob, opened via [BlobStore.openReader]. */
interface BlobReader : AutoCloseable {
    /** Reads up to `into.size` bytes into [into], returning the count read, or -1 at EOF. */
    fun read(into: ByteArray): Int
}
