package dev.azula.vmhost

import app.azula.vmhost.NativeXz
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** [NativeXz]'s calls, behind an interface so tests can stand in for the library. */
internal interface XzCodec {
    fun create(threads: Int, memLimit: Long): Long
    fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int, finish: Boolean): Long
    fun destroy(handle: Long)
}

internal object NativeXzCodec : XzCodec {
    override fun create(threads: Int, memLimit: Long) = NativeXz.create(threads, memLimit)
    override fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int, finish: Boolean) =
        NativeXz.decode(handle, input, inOff, inLen, output, outOff, outLen, finish)
    override fun destroy(handle: Long) = NativeXz.destroy(handle)
}

/** The native decoder gave up; [code] is its failure code. */
internal class NativeXzException(val code: Long) : IOException("xz: ${reason(code)}") {
    private companion object {
        fun reason(code: Long) = when (code) {
            -1L -> "not an xz file"
            -2L -> "corrupt data"
            -3L -> "the input ended early"
            -4L -> "out of memory"
            -5L -> "unsupported options"
            -6L -> "closed"
            else -> "decoder error $code"
        }
    }
}

/**
 * An xz stream decoded by [codec]. It takes whatever [source] has rather than waiting to fill a
 * buffer, and doesn't go back to [source] while the decoder has output ready, so it can follow a
 * file that is still growing. Closing it frees the decoder and closes [source].
 */
internal class NativeXzInputStream(
    private val source: InputStream,
    private val codec: XzCodec = NativeXzCodec,
    threads: Int = Xz.THREADS,
    memLimit: Long = Xz.MEM_LIMIT,
) : InputStream() {
    private var handle = codec.create(threads, memLimit)
    private val inBuf = ByteArray(256 * 1024)
    private var inPos = 0
    private var inEnd = 0
    private var sourceEnded = false
    /** The last call filled the caller's buffer, so there may be more without more input. */
    private var outputPending = false
    private var ended = false
    private val one = ByteArray(1)

    init { if (handle == 0L) throw NativeXzException(-4) }

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        while (!ended) {
            if (inPos == inEnd && !sourceEnded && !outputPending) {
                val n = source.read(inBuf, 0, inBuf.size)
                if (n < 0) sourceEnded = true else { inPos = 0; inEnd = n }
            }
            val r = codec.decode(handle, inBuf, inPos, inEnd - inPos, b, off, len, sourceEnded)
            if (r < 0) throw NativeXzException(r)
            inPos += (r and COUNT).toInt()
            ended = r shr 62 and 1L != 0L
            val produced = (r shr 31 and COUNT).toInt()
            outputPending = produced == len
            if (produced > 0) return produced
        }
        return -1
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) codec.destroy(h)
        source.close()
    }

    private companion object { const val COUNT = 0x7fff_ffffL }
}

/**
 * Opens an xz stream with the native decoder, or with the Java one where the library hasn't got
 * it ([native] is null). Asking loads the library, so it waits until there's something to decode.
 */
internal class Xz(private val native: () -> XzCodec? = { NativeXzCodec.takeIf { NativeXz.available } }) {
    /** For the log. */
    val name: String get() = if (native() != null) "native xz, threads=$THREADS" else "java xz"

    fun open(source: InputStream): InputStream = native()?.let { NativeXzInputStream(source, it) } ?: openJava(source)

    fun openJava(source: InputStream): InputStream = XZInputStream(BufferedInputStream(source, 256 * 1024))

    companion object {
        /** A core is left for the download. */
        val THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

        /**
         * Room for every thread: one of Debian's blocks takes about 40 MB while in flight (24 MiB out, 8 MiB
         * dictionary, the rest in). Reached when catching up with what's on disk, not at a download's pace.
         */
        const val MEM_LIMIT = 384L shl 20
    }
}
