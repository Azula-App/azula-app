package dev.azula.core

/**
 * Encodes/decodes the 32-byte device secret key as a **BIP-39 24-word recovery
 * phrase** — the crypto-wallet standard. The 256-bit key plus an 8-bit checksum
 * (the first byte of its SHA-256) pack into 24 × 11-bit indices over the standard
 * English wordlist ([BIP39_WORDS]). The checksum lets [decode] reject a mistyped or
 * wrong phrase instead of silently adopting a bogus identity.
 */
object RecoveryPhrase {
    private const val KEY_BYTES = 32
    private const val WORD_COUNT = 24 // (256 entropy + 8 checksum) / 11 bits

    /** 32-byte key → 24 lowercase, space-joined words. */
    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        val checksum = Sha256.hash(key)[0].toInt() and 0xFF
        val bits = ArrayList<Int>(WORD_COUNT * 11)
        for (b in key) { val v = b.toInt() and 0xFF; for (i in 7 downTo 0) bits.add((v ushr i) and 1) }
        for (i in 7 downTo 0) bits.add((checksum ushr i) and 1)

        val sb = StringBuilder()
        for (w in 0 until WORD_COUNT) {
            var idx = 0
            for (i in 0 until 11) idx = (idx shl 1) or bits[w * 11 + i]
            if (w > 0) sb.append(' ')
            sb.append(BIP39_WORDS[idx])
        }
        return sb.toString()
    }

    /** 24 words → 32-byte key, or null if the phrase is malformed or its checksum
     *  doesn't verify (unknown word, wrong count, typo, or a different phrase). */
    fun decode(phrase: String): ByteArray? {
        val words = phrase.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size != WORD_COUNT) return null

        val bits = ArrayList<Int>(WORD_COUNT * 11)
        for (word in words) {
            val idx = wordIndex[word] ?: return null
            for (i in 10 downTo 0) bits.add((idx ushr i) and 1)
        }

        val key = ByteArray(KEY_BYTES)
        for (i in 0 until KEY_BYTES) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or bits[i * 8 + j]
            key[i] = v.toByte()
        }
        var cs = 0
        for (j in 0 until 8) cs = (cs shl 1) or bits[KEY_BYTES * 8 + j]
        if (cs != (Sha256.hash(key)[0].toInt() and 0xFF)) return null
        return key
    }

    private val WHITESPACE = Regex("\\s+")
    private val wordIndex: Map<String, Int> by lazy {
        HashMap<String, Int>(BIP39_WORDS.size * 2).apply {
            BIP39_WORDS.forEachIndexed { i, w -> put(w, i) }
        }
    }
}
