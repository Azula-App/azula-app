package app.azula.vmhost.ftl

import java.io.InputStream
import java.security.MessageDigest

// What the steps of cloud.sh need that the phone's shell has no tool for: hashes, ELF headers, JSON.
// All pure, so it is unit-tested.

/** The size and SHA-256 of some bytes. */
data class Digest(val bytes: Long, val sha256: String) {
    companion object {
        fun of(input: InputStream): Digest {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var n = 0L
            while (true) {
                val got = input.read(buf)
                if (got < 0) break
                md.update(buf, 0, got)
                n += got
            }
            return Digest(n, md.digest().joinToString("") { "%02x".format(it) })
        }

        /** What `seq 1 [last]` prints. */
        fun ofSeq(last: Int): Digest =
            of(buildString { for (i in 1..last) append(i).append('\n') }.byteInputStream())

        private val SUM_LINE = Regex("""([0-9a-f]{64}) [ *].+""")

        /** The hash in `sha256sum FILE`'s one line of output; null when that isn't what it is. */
        fun fromSha256sum(output: String): String? =
            SUM_LINE.matchEntire(output.trimEnd('\n'))?.groupValues?.get(1)
    }
}

object Elf {
    private const val AARCH64 = 183

    /**
     * Why a file that starts with [head] isn't a 64-bit little-endian aarch64 ELF, or null when it is
     * one. `file`, which cloud.sh asks, says "ARM aarch64" for machine 183.
     */
    fun notAarch64(head: ByteArray): String? = when {
        head.size < 20 -> "${head.size} bytes, too short for an ELF header"
        String(head, 0, 4, Charsets.ISO_8859_1) != "\u007fELF" -> "not an ELF file"
        head[4].toInt() != 2 -> "ELF class ${head[4]}, not 64-bit"
        head[5].toInt() != 1 -> "ELF byte order ${head[5]}, not little-endian"
        machine(head) != AARCH64 -> "ELF machine ${machine(head)}, not $AARCH64 (aarch64)"
        else -> null
    }

    private fun machine(h: ByteArray): Int = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
}

object JobRef {
    private val REF = Regex("""[a-z0-9-]+/(j_[0-9a-f]+)""")

    /** The id in a job ref (`vm/j_…`, what `exec --detach` prints); null when [ref] isn't one. */
    fun id(ref: String): String? = REF.matchEntire(ref)?.groupValues?.get(1)
}

object Jobs {
    /**
     * The `ref` of the first job labelled [label] in `azula compute jobs --json`; null when there is
     * none, or it has no ref. Throws [IllegalArgumentException] on anything but a JSON array.
     */
    fun refByLabel(json: String, label: String): String? {
        val jobs = requireNotNull(Json.parse(json) as? List<*>) { "jobs --json isn't an array" }
        val job = jobs.firstOrNull { (it as? Map<*, *>)?.get("label") == label } as? Map<*, *>
        return job?.get("ref") as? String
    }
}

/** What azula says on stderr when the connection it was using goes away. */
object Notice {
    /** `azula: connection lost, reattaching to vm/j_…` */
    fun reattached(stderr: String): Boolean = "reattach" in stderr

    /**
     * `azula: connection lost, resuming push at 2.9 MiB`. Not the last line's "(resumed from …)": a
     * transfer that an earlier run left unfinished says that too, with no fault in this one.
     */
    fun resumed(stderr: String, verb: String): Boolean = "resuming $verb" in stderr

    /** What iroh logs, once per connection to the relay, when the relay starts to hold that end's traffic back. */
    const val THROTTLED = "The relay is rate-limiting this endpoint"

    fun throttled(stderr: String): Boolean = THROTTLED in stderr

    private val LOG_LINE = Regex("""\d{4}-\d\d-\d\dT[\d:.]+Z +(TRACE|DEBUG|INFO|WARN|ERROR) .*""")

    /** [stderr] without its log lines, which is what azula itself said. */
    fun withoutLog(stderr: String): String = stderr.lines().filterNot { LOG_LINE.matches(it) }.joinToString("\n")
}

/**
 * A reader for the JSON azula prints (org.json is Android's, and only a stub in unit tests). Objects
 * are maps, arrays lists, numbers doubles. Throws [IllegalArgumentException] on anything that isn't
 * one whole JSON value.
 */
object Json {
    fun parse(text: String): Any? {
        val r = Reader(text)
        val v = r.value()
        r.space()
        require(r.i == text.length) { "more text after the JSON, at ${r.i}" }
        return v
    }

    private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?""")

    private class Reader(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            space()
            require(i < s.length) { "the JSON ends early" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun number(): Double {
            val m = NUMBER.find(s, i)
            require(m != null && m.range.first == i) { "unexpected '${s[i]}' at $i" }
            i = m.range.last + 1
            return m.value.toDouble()
        }

        private fun <T> word(w: String, v: T): T {
            require(s.startsWith(w, i)) { "unexpected '${s[i]}' at $i" }
            i += w.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            if (empty('}')) return out
            do {
                space()
                require(i < s.length && s[i] == '"') { "expected a key at $i" }
                val key = string()
                space()
                require(i < s.length && s[i] == ':') { "expected ':' at $i" }
                i++
                out[key] = value()
            } while (more('}'))
            return out
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            if (empty(']')) return out
            do out += value() while (more(']'))
            return out
        }

        private fun empty(close: Char): Boolean {
            space()
            return (i < s.length && s[i] == close).also { if (it) i++ }
        }

        /** After an element: true at a comma, false at [close]. */
        private fun more(close: Char): Boolean {
            space()
            require(i < s.length && (s[i] == close || s[i] == ',')) { "expected '$close' or ',' at $i" }
            return s[i++] == ','
        }

        private fun string(): String {
            val out = StringBuilder()
            i++
            while (true) {
                require(i < s.length) { "a string doesn't end" }
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            require(i < s.length) { "a string doesn't end" }
            return when (val e = s[i++]) {
                '"', '\\', '/' -> e
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000c'
                'u' -> {
                    val hex = s.drop(i).take(4)
                    require(hex.length == 4 && hex.all { it in "0123456789abcdefABCDEF" }) { "bad \\u escape at $i" }
                    i += 4
                    hex.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException("bad escape '\\$e' at $i")
            }
        }
    }
}
