package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    private val token = "azc1.eyJ2IjoxLCJob3N0IjoiYWJjIn0_-x"

    @Test fun removesTokensByShape() {
        val s = Scrub()
        assertTrue(s.leaks("export AZULA_COMPUTE_TOKEN=$token"))
        val clean = s.clean("export AZULA_COMPUTE_TOKEN=$token; next")
        assertEquals("export AZULA_COMPUTE_TOKEN=<redacted>; next", clean)
        assertFalse(s.leaks(clean))
    }

    @Test fun removesTokensInJson() {
        assertEquals("""{"id":"1","token":"<redacted>","secret":"<redacted>"}""", Scrub().clean("""{"id":"1","token":"whatever","secret":"s"}"""))
    }

    @Test fun removesASeenTokenWithoutItsPrefix() {
        val s = Scrub().apply { add(token) }
        val bare = token.substringAfter('.')
        assertTrue(s.leaks("payload=$bare"))
        assertFalse(s.clean("payload=$bare and $token").contains(bare))
    }

    @Test fun leavesOtherTextAlone() {
        val text = "grant 12ab -> token.json\nazula 0.4.2\n✓ token        0 ms  grant a, host b"
        assertEquals(text, Scrub().apply { add(token) }.clean(text))
    }
}
