package dev.azula.sync

// A minimal, dependency-free lowercase-hex codec -- `core`'s DeviceCert.kt/EventLog.kt
// keep their own wire-format helpers `internal` (module-scoped), so this module needs
// its own: `EventLogStore.vector()`'s hex device-pk keys, `sync-real`'s per-device
// `<hex>.jsonl` filenames, and the fold's hex contact/conversation keys all need it.
// No `java.lang.Character` (or any other JVM-only API): this module targets iOS too.

private const val HEX_CHARS = "0123456789abcdef"

/** Lowercase hex encoding -- the convention used throughout this change for device
 *  public keys, contact/conversation keys, and vector maps. */
fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private fun hexNibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Decodes a hex string (either case); `null` on odd length or any non-hex character. */
fun String.hexToByteArrayOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = hexNibble(this[i * 2])
        val lo = hexNibble(this[i * 2 + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
