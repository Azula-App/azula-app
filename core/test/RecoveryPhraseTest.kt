import dev.azula.core.RecoveryPhrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryPhraseTest {

    @Test
    fun standardAllZeroVector() {
        // The canonical BIP-39 256-bit all-zero-entropy mnemonic.
        val expected = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon art"
        assertEquals(expected, RecoveryPhrase.encode(ByteArray(32)))
    }

    @Test
    fun standardAllOnesVector() {
        // 256 bits of 0xff → the standard "zoo … zoo vote" mnemonic.
        val key = ByteArray(32) { 0xFF.toByte() }
        val expected = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo " +
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"
        assertEquals(expected, RecoveryPhrase.encode(key))
    }

    @Test
    fun roundTrips() {
        for (seed in 0 until 50) {
            // A deterministic pseudo-random 32-byte key (no Random needed).
            val key = ByteArray(32) { ((seed * 31 + it * 17 + 7) and 0xFF).toByte() }
            val phrase = RecoveryPhrase.encode(key)
            assertEquals(24, phrase.split(" ").size)
            assertTrue(key.contentEquals(RecoveryPhrase.decode(phrase)), "round-trip failed for seed=$seed")
        }
    }

    @Test
    fun decodeNormalizesWhitespaceAndCase() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val phrase = RecoveryPhrase.encode(key)
        val messy = "  " + phrase.uppercase().replace(" ", "   ") + "\n"
        assertTrue(key.contentEquals(RecoveryPhrase.decode(messy)))
    }

    @Test
    fun rejectsBadChecksum() {
        // All-"abandon" is 24 valid words but the wrong checksum (real one ends "art").
        assertNull(RecoveryPhrase.decode("abandon ".repeat(24).trim()))
    }

    @Test
    fun rejectsUnknownWord() {
        val phrase = RecoveryPhrase.encode(ByteArray(32) { 1 })
        val broken = phrase.split(" ").toMutableList().also { it[5] = "notaword" }.joinToString(" ")
        assertNull(RecoveryPhrase.decode(broken))
    }

    @Test
    fun rejectsWrongWordCount() {
        assertNull(RecoveryPhrase.decode("abandon abandon abandon"))
        assertNull(RecoveryPhrase.decode(""))
    }
}
