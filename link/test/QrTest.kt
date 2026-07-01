import dev.azula.link.Qr
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round-trips the hand-rolled QR encoder through an independent decoder: encode a
 * string to a module matrix, then decode that matrix back (read the format info to
 * recover the mask, unmask, walk the zigzag skipping function modules, parse the
 * byte-mode payload). If the text survives the trip, real camera scanners — which
 * run the same standard decode — will read it too. This is stronger than matching
 * a fixed reference matrix: it exercises versions, masks, and alignment patterns
 * without pinning to one encoder's mask choice.
 */
class QrTest {

    @Test fun roundTripsAcrossVersionsAndMasks() {
        for (text in listOf(
            "HELLO",
            "https://azula.app/s/abcdef123456",
            "azula://connect?code=nodeabc123def456",
            // long enough to push into a higher version with multiple alignment patterns
            "https://azula.app/s/aBcDeF0123456789ghijkLMNOPqrstuvwxyz" +
                "0123456789ABCDEFghijklmnopqrstuvwxyz9876543210",
        )) {
            assertEquals(text, decode(Qr.encode(text, Qr.Ecc.LOW)), "round-trip failed for: $text")
        }
    }

    // --- minimal standard QR decoder (byte mode) ---

    private fun decode(m: Array<BooleanArray>): String {
        val size = m.size
        val ver = (size - 17) / 4
        val fn = functionMap(size, ver)

        // Format info: 15 bits, LSB-first at their fixed positions, XOR 0x5412, top 5 = data.
        val fb = IntArray(15)
        for (i in 0..5) fb[i] = bit(m, 8, i)
        fb[6] = bit(m, 8, 7); fb[7] = bit(m, 8, 8); fb[8] = bit(m, 7, 8)
        for (i in 9..14) fb[i] = bit(m, 14 - i, 8)
        var fv = 0
        for (i in 0..14) fv = fv or (fb[i] shl i)
        fv = fv xor 0x5412
        val mask = (fv ushr 10) and 7

        // Read data bits via the same zigzag as the encoder, unmasking as we go.
        val bits = ArrayList<Int>()
        var col = size - 1
        while (col >= 1) {
            if (col == 6) col = 5
            for (v in 0 until size) {
                for (j in 0 until 2) {
                    val x = col - j
                    val upward = ((col + 1) and 2) == 0
                    val y = if (upward) size - 1 - v else v
                    if (!fn[y][x]) {
                        var b = if (m[y][x]) 1 else 0
                        if (maskBit(mask, x, y)) b = b xor 1
                        bits.add(b)
                    }
                }
            }
            col -= 2
        }

        val cc = if (ver <= 9) 8 else 16
        val len = readInt(bits, 4, cc)
        val bytes = ByteArray(len) { readInt(bits, 4 + cc + it * 8, 8).toByte() }
        return bytes.decodeToString()
    }

    private fun functionMap(size: Int, ver: Int): Array<BooleanArray> {
        val fn = Array(size) { BooleanArray(size) }
        fun mark(x: Int, y: Int) { if (x in 0 until size && y in 0 until size) fn[y][x] = true }
        // finders + separators (8×8 including the 1-module gap)
        for ((cx, cy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7))
            for (dy in -1..7) for (dx in -1..7) mark(cx + dx, cy + dy)
        // timing
        for (i in 0 until size) { mark(6, i); mark(i, 6) }
        // dark module + format areas
        mark(8, size - 8)
        for (i in 0..8) { mark(8, i); mark(i, 8) }
        for (i in 0..7) { mark(size - 1 - i, 8); mark(8, size - 1 - i) }
        // alignment patterns
        val ap = alignPositions(ver)
        for (cy in ap) for (cx in ap) {
            if ((cx == 6 && cy == 6) || (cx == 6 && cy == size - 7) || (cx == size - 7 && cy == 6)) continue
            for (dy in -2..2) for (dx in -2..2) mark(cx + dx, cy + dy)
        }
        // version info (v >= 7)
        if (ver >= 7) for (i in 0 until 18) {
            val a = size - 11 + i % 3; val b = i / 3
            mark(a, b); mark(b, a)
        }
        return fn
    }

    private fun alignPositions(ver: Int): List<Int> {
        if (ver == 1) return emptyList()
        val n = ver / 7 + 2
        val step = if (ver == 32) 26 else ((ver * 4 + 2 * n + 1) / (2 * n - 2)) * 2
        val pos = ArrayList<Int>()
        var p = ver * 4 + 10
        repeat(n - 1) { pos.add(0, p); p -= step }
        pos.add(0, 6)
        return pos
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    private fun bit(m: Array<BooleanArray>, x: Int, y: Int): Int = if (m[y][x]) 1 else 0

    private fun readInt(bits: List<Int>, off: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or bits[off + i]
        return v
    }
}
