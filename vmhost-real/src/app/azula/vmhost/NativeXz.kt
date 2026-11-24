package app.azula.vmhost

/**
 * JNI surface of `libazula_host.so`'s xz decoder (azula-cli's `android-host-jni`, `xz.rs`):
 * liblzma, decoding a file's blocks on several threads at once.
 *
 * **Do not rename or move this object or its methods** (see [AzulaHost]): the library's
 * exported symbols are `Java_app_azula_vmhost_NativeXz_*`.
 *
 * A handle is checked on the native side: one that's unknown or already destroyed fails, it
 * doesn't crash. Check [available] before anything else.
 */
object NativeXz {
    /** The library decodes xz. */
    const val CAP_DECODE = 1

    /** [create]'s `threads` is honoured. */
    const val CAP_THREADS = 2

    /**
     * Whether the library is here and has the decoder. False with no library (JVM tests) and
     * with one from before the decoder; the Java decoder stands in for both.
     */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("azula_host")
            capabilities() and CAP_DECODE != 0
        } catch (_: LinkageError) {
            false
        }
    }

    /** `CAP_*` bits. */
    @JvmStatic external fun capabilities(): Int

    /**
     * A decoder's handle, or 0. [memLimit] bounds the memory of blocks in flight, in bytes; it is
     * soft (a single block that needs more decodes alone).
     */
    @JvmStatic external fun create(threads: Int, memLimit: Long): Long

    /**
     * Takes some of `input[inOff, inOff + inLen)` and gives some of `output[outOff, outOff + outLen)`.
     * [finish]: no input follows what this call passes. Returns a negative failure code, or
     * `consumed | produced shl 31 | end shl 62`; `end` only ever comes with [finish].
     */
    @JvmStatic external fun decode(
        handle: Long, input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int, finish: Boolean,
    ): Long

    /** Idempotent. */
    @JvmStatic external fun destroy(handle: Long)
}
