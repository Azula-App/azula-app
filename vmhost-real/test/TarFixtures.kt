import java.io.ByteArrayOutputStream

/** Builds tiny tar archives for the reader tests, including GNU old-format sparse members like Debian's disk.raw. */
object TarFixtures {
    private fun octal(v: Long, len: Int): ByteArray = (v.toString(8).padStart(len - 1, '0') + "\u0000").toByteArray()

    private fun header(name: String, type: Char, size: Long, magic: String = "ustar  \u0000"): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        octal(420, 8).copyInto(h, 100)
        octal(0, 8).copyInto(h, 108)
        octal(0, 8).copyInto(h, 116)
        octal(size, 12).copyInto(h, 124)
        octal(0, 12).copyInto(h, 136)
        h[156] = type.code.toByte()
        magic.toByteArray(Charsets.ISO_8859_1).copyInto(h, 257)
        return h
    }

    private fun seal(h: ByteArray): ByteArray {
        for (i in 148..155) h[i] = 32
        val sum = h.sumOf { it.toInt() and 0xff }
        (sum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(h, 148)
        return h
    }

    private fun pad(out: ByteArrayOutputStream, n: Int) { out.write(ByteArray((512 - n % 512) % 512)) }

    fun file(out: ByteArrayOutputStream, name: String, data: ByteArray) {
        out.write(seal(header(name, '0', data.size.toLong())))
        out.write(data); pad(out, data.size)
    }

    /** `segments` are (offset, bytes) in order; the unpacked file is [real] bytes long. */
    fun sparse(out: ByteArrayOutputStream, name: String, real: Long, segments: List<Pair<Long, ByteArray>>) {
        val stored = segments.sumOf { it.second.size }
        val h = header(name, 'S', stored.toLong())
        fun put(into: ByteArray, at: Int, segs: List<Pair<Long, ByteArray>>) {
            segs.forEachIndexed { i, (off, d) ->
                octal(off, 12).copyInto(into, at + i * 24)
                octal(d.size.toLong(), 12).copyInto(into, at + i * 24 + 12)
            }
        }
        put(h, 386, segments.take(4))
        val rest = segments.drop(4)
        h[482] = if (rest.isEmpty()) 0 else 1
        octal(real, 12).copyInto(h, 483)
        out.write(seal(h))
        var r = rest
        while (r.isNotEmpty()) {
            val ext = ByteArray(512)
            put(ext, 0, r.take(21))
            r = r.drop(21)
            ext[504] = if (r.isEmpty()) 0 else 1
            out.write(ext)
        }
        for ((_, d) in segments) out.write(d)
        pad(out, stored)
    }

    fun longName(out: ByteArrayOutputStream, name: String, type: Char = '0', data: ByteArray) {
        val nameBytes = (name + "\u0000").toByteArray()
        out.write(seal(header("././@LongLink", 'L', nameBytes.size.toLong())))
        out.write(nameBytes); pad(out, nameBytes.size)
        file(out, name.take(50), data)
    }

    fun pax(out: ByteArrayOutputStream, path: String) {
        var rec = " path=$path\n"
        var len = rec.length + 1
        while ((len.toString() + rec).length != len) len = (len.toString() + rec).length
        val text = (len.toString() + rec).toByteArray()
        out.write(seal(header("PaxHeader", 'x', text.size.toLong(), "ustar\u000000")))
        out.write(text); pad(out, text.size)
    }

    fun end(out: ByteArrayOutputStream) { out.write(ByteArray(1024)) }
}
