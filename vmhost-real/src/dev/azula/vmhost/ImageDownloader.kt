package dev.azula.vmhost

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A network failure worth retrying (the `.part` file is kept). */
internal open class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server's copy of the archive isn't the one this download started on: its bytes, size and checksum are stale. */
internal class ImageChangedException(message: String) : NetworkException(message)

internal class CancelledException : RuntimeException("cancelled")

/** What a HEAD says about the archive: its size and a validator for `If-Range`. */
internal data class RemoteInfo(val bytes: Long, val validator: String)

/**
 * HTTPS-only fetching: follows redirects by hand so every hop can be checked
 * ([httpsOnly] is off only in tests, which serve from localhost).
 */
internal class HttpFetch(private val httpsOnly: Boolean = true) {
    class Response(val code: Int, private val conn: HttpURLConnection) : AutoCloseable {
        fun header(name: String): String? = conn.getHeaderField(name)
        val contentLength: Long get() = conn.getHeaderFieldLong("Content-Length", -1)
        val stream get() = conn.inputStream
        override fun close() { runCatching { conn.disconnect() } }
    }

    fun open(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (httpsOnly && target.protocol != "https") throw NetworkException("refusing a non-https url: $target")
            val conn = try {
                (target.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    // No transparent gzip: it would hide Content-Length and break Range arithmetic.
                    setRequestProperty("Accept-Encoding", "identity")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            } catch (e: IOException) { throw NetworkException("can't reach ${target.host}", e) }
            val code = try { conn.responseCode } catch (e: IOException) {
                conn.disconnect(); throw NetworkException("can't reach ${target.host}", e)
            }
            if (code in REDIRECTS) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                target = URL(target, loc ?: throw NetworkException("redirect without a location"))
                return@repeat
            }
            return Response(code, conn)
        }
        throw NetworkException("too many redirects")
    }

    fun text(url: String): String = open(url).use { r ->
        if (r.code != 200) throw NetworkException("$url answered ${r.code}")
        try { r.stream.readBytes().toString(Charsets.UTF_8) } catch (e: IOException) { throw NetworkException("download interrupted", e) }
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** The archive download: a HEAD for its real size, then a resumable ranged GET into a `.part` file. */
internal class ImageDownloader(private val http: HttpFetch = HttpFetch()) {

    fun probe(url: String): RemoteInfo = http.open(url, "HEAD").use { r ->
        if (r.code != 200) throw NetworkException("the image server answered ${r.code}")
        val len = r.contentLength
        if (len <= 0) throw NetworkException("the image server didn't say how big the image is")
        RemoteInfo(len, r.header("ETag") ?: r.header("Last-Modified") ?: "")
    }

    /**
     * Brings [part] up to [info.bytes] bytes, from wherever it already is. Returns the offset
     * the transfer started at (0 when the server ignored the range, or the file changed).
     * Throws [NetworkException] on any failure; whatever was written stays for the next try.
     * [progress] gets the total bytes on disk.
     */
    fun download(url: String, part: File, info: RemoteInfo, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
        var offset = Resume.startOffset(if (part.exists()) part.length() else 0, info.bytes)
        if (part.exists() && part.length() != offset) FileOutputStream(part, true).channel.use { it.truncate(offset) }
        if (offset == info.bytes) { progress(offset); return offset }
        val headers = HashMap<String, String>()
        if (offset > 0) {
            headers["Range"] = "bytes=$offset-"
            if (info.validator.isNotEmpty()) headers["If-Range"] = info.validator
        }
        http.open(url, "GET", headers).use { r ->
            when (r.code) {
                206 -> {
                    val cr = Resume.parseContentRange(r.header("Content-Range"))
                    if (cr != null && cr.third != info.bytes) throw ImageChangedException("the image changed on the server")
                    if (cr == null || cr.first != offset) {
                        throw NetworkException("the server answered a different range than asked for")
                    }
                }
                200 -> {
                    // The server ignored the range (or the file changed): start over.
                    offset = 0
                    if (r.contentLength != info.bytes) throw ImageChangedException("the image changed on the server")
                }
                else -> throw NetworkException("the image server answered ${r.code}")
            }
            try {
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = offset
                    progress(total)
                    r.stream.use { inp ->
                        while (true) {
                            if (cancelled()) throw CancelledException()
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            progress(total)
                        }
                    }
                }
            } catch (e: CancelledException) { throw e
            } catch (e: IOException) { throw NetworkException("download interrupted", e) }
        }
        if (part.length() != info.bytes) throw NetworkException("download ended early (${part.length()} of ${info.bytes} bytes)")
        return offset
    }
}
