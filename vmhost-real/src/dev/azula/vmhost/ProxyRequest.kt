package dev.azula.vmhost

import java.net.URI

/** Where a guest's proxy request wants to go, parsed from its request head. */
internal data class ProxyTarget(
    /** CONNECT (a TCP tunnel) rather than an absolute-form plain request. */
    val connect: Boolean,
    val host: String,
    val port: Int,
    /** The request method as sent, for logs. */
    val method: String,
    /** Offset just past the blank line ending the head; bytes beyond it were over-read. */
    val headEnd: Int,
)

/** Pure parsing for [Proxy]: the HTTP/1.x proxy request forms the guest sends. */
internal object ProxyRequest {
    const val MAX_HEAD = 16 * 1024

    /** Index of the `\r\n\r\n` ending the head (its first byte), or -1. */
    fun indexOfHeadEnd(b: ByteArray, len: Int = b.size): Int {
        var i = 0
        while (i + 3 < len) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() &&
                b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()
            ) return i
            i++
        }
        return -1
    }

    /**
     * Parses [data] (a head, possibly followed by over-read body bytes). Accepts
     * `CONNECT host:port` (port defaults to 443; bracketed IPv6) and absolute-form
     * `METHOD http(s)://host[:port]/path` (port defaults to 80 or 443). Anything else
     * is rejected with an [IllegalArgumentException] whose message is loggable.
     */
    fun parse(data: ByteArray): ProxyTarget {
        val endIdx = indexOfHeadEnd(data)
        require(endIdx >= 0) { "incomplete request head" }
        val lineEnd = String(data, 0, minOf(endIdx, 2048), Charsets.ISO_8859_1).indexOf("\r\n")
        val line = String(data, 0, if (lineEnd >= 0) lineEnd else minOf(endIdx, 2048), Charsets.ISO_8859_1)
        val parts = line.split(" ")
        require(parts.size >= 2) { "malformed request line" }
        val method = parts[0]
        val headEnd = endIdx + 4
        return if (method.equals("CONNECT", ignoreCase = true)) {
            val hp = parts[1]
            val c = hp.lastIndexOf(':')
            val rawHost = if (c >= 0) hp.substring(0, c) else hp
            val port = if (c >= 0) hp.substring(c + 1).toIntOrNull() ?: throw IllegalArgumentException("bad port in $hp") else 443
            require(port in 1..65535) { "bad port in $hp" }
            ProxyTarget(true, unbracket(rawHost), port, method, headEnd)
        } else {
            val u = try { URI.create(parts[1]) } catch (e: IllegalArgumentException) { throw IllegalArgumentException("bad uri: ${parts[1]}") }
            val host = u.host ?: throw IllegalArgumentException("not absolute-form: ${parts[1]}")
            val port = if (u.port >= 0) u.port else if ("https".equals(u.scheme, ignoreCase = true)) 443 else 80
            ProxyTarget(false, unbracket(host), port, method, headEnd)
        }
    }

    private fun unbracket(h: String) = if (h.startsWith("[") && h.endsWith("]")) h.substring(1, h.length - 1) else h
}
