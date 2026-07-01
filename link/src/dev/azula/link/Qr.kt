package dev.azula.link

/**
 * A minimal, dependency-free QR Code encoder — byte mode, versions 1–40 with
 * automatic sizing and mask selection. Returns the module matrix (true = dark)
 * for rendering. Enough to encode an azula connect link so any phone camera can
 * scan it. Ported from the standard QR algorithm; QrTest round-trips the output
 * through a decoder to prove it scans.
 */
object Qr {
    /** Error-correction level. [formatBits] is the 2-bit value used in the format info. */
    enum class Ecc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

    /** Encode [text] and return a size×size matrix of modules (true = dark). */
    fun encode(text: String, ecc: Ecc = Ecc.LOW): Array<BooleanArray> {
        val data = text.encodeToByteArray()
        val version = pickVersion(data.size, ecc)
        val allCodewords = addEccAndInterleave(makeDataCodewords(data, version, ecc), version, ecc)

        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, isFunction, version, ecc)
        drawCodewords(modules, isFunction, allCodewords)

        // Choose the mask with the lowest penalty (per spec), then keep it applied.
        var bestMask = 0
        var minPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, isFunction, mask)
            drawFormatBits(modules, isFunction, ecc, mask)
            val penalty = penaltyScore(modules)
            if (penalty < minPenalty) { minPenalty = penalty; bestMask = mask }
            applyMask(modules, isFunction, mask) // XOR again to undo
        }
        applyMask(modules, isFunction, bestMask)
        drawFormatBits(modules, isFunction, ecc, bestMask)
        return modules
    }

    // ---- capacity / version ----

    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun numDataCodewords(ver: Int, ecc: Ecc): Int =
        numRawDataModules(ver) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][ver]

    private fun charCountBits(ver: Int): Int = if (ver <= 9) 8 else 16 // byte mode

    private fun pickVersion(dataLen: Int, ecc: Ecc): Int {
        for (ver in 1..40) {
            val capacityBits = numDataCodewords(ver, ecc) * 8
            val usedBits = 4 + charCountBits(ver) + dataLen * 8
            if (usedBits <= capacityBits) return ver
        }
        throw IllegalArgumentException("data too long for a QR code")
    }

    // ---- data codewords (mode + count + bytes + padding) ----

    private fun makeDataCodewords(data: ByteArray, ver: Int, ecc: Ecc): ByteArray {
        val bb = BitBuffer()
        bb.append(0b0100, 4)                 // byte mode
        bb.append(data.size, charCountBits(ver))
        for (b in data) bb.append(b.toInt() and 0xFF, 8)

        val capacityBits = numDataCodewords(ver, ecc) * 8
        bb.append(0, minOf(4, capacityBits - bb.size)) // terminator
        bb.append(0, (8 - bb.size % 8) % 8)            // pad to byte boundary
        var pad = 0xEC
        while (bb.size < capacityBits) { bb.append(pad, 8); pad = pad xor (0xEC xor 0x11) }

        val out = ByteArray(bb.size / 8)
        for (i in out.indices) out[i] = bb.byteAt(i)
        return out
    }

    // ---- Reed–Solomon + block interleaving ----

    private fun addEccAndInterleave(data: ByteArray, ver: Int, ecc: Ecc): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][ver]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][ver]
        val rawCodewords = numRawDataModules(ver) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val blocks = ArrayList<ByteArray>()
        val rsDiv = reedSolomonDivisor(blockEccLen)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val block = dat.copyOf(shortBlockLen + 1) // room; short blocks leave a gap at [shortBlockLen-blockEccLen]
            val eccBytes = reedSolomonRemainder(dat, rsDiv)
            for (j in eccBytes.indices) block[block.size - blockEccLen + j] = eccBytes[j]
            blocks.add(block)
        }

        val result = ByteArray(rawCodewords)
        k = 0
        var i = 0
        while (i < blocks[0].size) {
            for (j in blocks.indices) {
                // Skip the padding cell in short data blocks (keeps interleave aligned).
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k] = blocks[j][i]; k++
                }
            }
            i++
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = rsMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
            }
            root = rsMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = (result[i].toInt() xor rsMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
        }
        return result
    }

    private fun rsMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ---- matrix drawing ----

    private fun drawFunctionPatterns(m: Array<BooleanArray>, fn: Array<BooleanArray>, ver: Int, ecc: Ecc) {
        val size = m.size
        // Timing patterns.
        for (i in 0 until size) {
            setFn(m, fn, 6, i, i % 2 == 0)
            setFn(m, fn, i, 6, i % 2 == 0)
        }
        // Finder patterns (+ separators) at three corners.
        drawFinder(m, fn, 3, 3)
        drawFinder(m, fn, size - 4, 3)
        drawFinder(m, fn, 3, size - 4)
        // Alignment patterns.
        val align = alignmentPositions(ver)
        val n = align.size
        for (i in 0 until n) for (j in 0 until n) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
            drawAlignment(m, fn, align[i], align[j])
        }
        // Reserve format + version areas (filled properly later); dark module.
        drawFormatBits(m, fn, ecc, 0) // reserves; overwritten with the real mask later
        drawVersion(m, fn, ver)
    }

    private fun drawFinder(m: Array<BooleanArray>, fn: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val x = cx + dx; val y = cy + dy
            if (x in m.indices && y in m.indices) setFn(m, fn, x, y, dist != 2 && dist != 4)
        }
    }

    private fun drawAlignment(m: Array<BooleanArray>, fn: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            setFn(m, fn, cx + dx, cy + dy, dist != 1)
        }
    }

    private fun drawFormatBits(m: Array<BooleanArray>, fn: Array<BooleanArray>, ecc: Ecc, mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        val size = m.size
        // Top-left / around finders.
        for (i in 0..5) setFn(m, fn, 8, i, getBit(bits, i))
        setFn(m, fn, 8, 7, getBit(bits, 6))
        setFn(m, fn, 8, 8, getBit(bits, 7))
        setFn(m, fn, 7, 8, getBit(bits, 8))
        for (i in 9 until 15) setFn(m, fn, 14 - i, 8, getBit(bits, i))
        // Second copy.
        for (i in 0 until 8) setFn(m, fn, size - 1 - i, 8, getBit(bits, i))
        for (i in 8 until 15) setFn(m, fn, 8, size - 15 + i, getBit(bits, i))
        setFn(m, fn, 8, size - 8, true) // always-dark module
    }

    private fun drawVersion(m: Array<BooleanArray>, fn: Array<BooleanArray>, ver: Int) {
        if (ver < 7) return
        var rem = ver
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (ver shl 12) or rem
        val size = m.size
        for (i in 0 until 18) {
            val bit = getBit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFn(m, fn, a, b, bit)
            setFn(m, fn, b, a, bit)
        }
    }

    private fun setFn(m: Array<BooleanArray>, fn: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark
        fn[y][x] = true
    }

    private fun drawCodewords(m: Array<BooleanArray>, fn: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0 // bit index
        var col = size - 1
        while (col >= 1) {
            if (col == 6) col = 5
            for (v in 0 until size) {
                for (j in 0 until 2) {
                    val x = col - j
                    val upward = ((col + 1) and 2) == 0
                    val y = if (upward) size - 1 - v else v
                    if (!fn[y][x] && i < data.size * 8) {
                        m[y][x] = getBit(data[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
            }
            col -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, fn: Array<BooleanArray>, mask: Int) {
        for (y in m.indices) for (x in m.indices) {
            if (fn[y][x]) continue
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> (x * y) % 2 + (x * y) % 3 == 0
                6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                else -> false
            }
            if (invert) m[y][x] = !m[y][x]
        }
    }

    // ---- penalty (mask selection) ----

    private fun penaltyScore(m: Array<BooleanArray>): Int {
        val size = m.size
        var score = 0
        // Rule 1: runs of 5+ same-color in rows and columns.
        for (y in 0 until size) {
            var runColor = false; var runLen = 0
            for (x in 0 until size) {
                if (m[y][x] == runColor) { runLen++; if (runLen == 5) score += 3 else if (runLen > 5) score++ }
                else { runColor = m[y][x]; runLen = 1 }
            }
        }
        for (x in 0 until size) {
            var runColor = false; var runLen = 0
            for (y in 0 until size) {
                if (m[y][x] == runColor) { runLen++; if (runLen == 5) score += 3 else if (runLen > 5) score++ }
                else { runColor = m[y][x]; runLen = 1 }
            }
        }
        // Rule 2: 2x2 blocks of same color.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3
        }
        // Rule 3: finder-like patterns in rows and columns.
        val pattern = booleanArrayOf(true, false, true, true, true, false, true)
        for (y in 0 until size) for (x in 0..size - 7) {
            if (matches(m, x, y, pattern, true)) {
                if ((x >= 4 && allLight(m, x - 4, y, 4, true)) || (x + 7 + 4 <= size && allLight(m, x + 7, y, 4, true))) score += 40
            }
        }
        for (x in 0 until size) for (y in 0..size - 7) {
            if (matches(m, x, y, pattern, false)) {
                if ((y >= 4 && allLight(m, x, y - 4, 4, false)) || (y + 7 + 4 <= size && allLight(m, x, y + 7, 4, false))) score += 40
            }
        }
        // Rule 4: overall dark/light balance.
        var dark = 0
        for (y in 0 until size) for (x in 0 until size) if (m[y][x]) dark++
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        score += k * 10
        return score
    }

    private fun matches(m: Array<BooleanArray>, x: Int, y: Int, p: BooleanArray, horiz: Boolean): Boolean {
        for (i in p.indices) {
            val v = if (horiz) m[y][x + i] else m[y + i][x]
            if (v != p[i]) return false
        }
        return true
    }

    private fun allLight(m: Array<BooleanArray>, x: Int, y: Int, n: Int, horiz: Boolean): Boolean {
        for (i in 0 until n) {
            val v = if (horiz) m[y][x + i] else m[y + i][x]
            if (v) return false
        }
        return true
    }

    private fun alignmentPositions(ver: Int): IntArray {
        if (ver == 1) return IntArray(0)
        val numAlign = ver / 7 + 2
        val step = if (ver == 32) 26 else (ver * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = ver * 4 + 10
        for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    private fun getBit(x: Int, i: Int): Boolean = ((x ushr i) and 1) != 0

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
        fun byteAt(index: Int): Byte {
            var b = 0
            for (i in 0 until 8) b = (b shl 1) or (if (bits[index * 8 + i]) 1 else 0)
            return b.toByte()
        }
    }

    // Standard QR tables (index by [ecc.ordinal][version]; version 0 unused).
    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )
    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )
}
