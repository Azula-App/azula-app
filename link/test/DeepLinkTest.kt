import dev.azula.link.AzulaLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    private val payload = "aziaeaaci2fm6e2xtppnfk3saaaaaaaaabamf5hk3dbfv2gk43ufvsw4zdqn5uw45bnoruwg23foqwwe6lumvzq"

    @Test
    fun buildsInviteLink() {
        assertEquals("https://azula.app/i/$payload", AzulaLinks.inviteLink(payload))
    }

    @Test
    fun parsesInviteWebLink() {
        assertEquals(payload, AzulaLinks.parse(AzulaLinks.inviteLink(payload)))
    }

    @Test
    fun parsesInviteCustomScheme() {
        assertEquals(payload, AzulaLinks.parse("azula://i?c=$payload"))
    }

    @Test
    fun parsesBareInviteString() {
        assertEquals(payload, AzulaLinks.parse(payload))
        assertEquals(payload, AzulaLinks.parse("  $payload  "))
    }

    @Test
    fun percentDecodesInviteCustomScheme() {
        // '/' → %2F to prove percent-decoding still runs for the invite form.
        val encoded = "azi%2Fabc"
        assertEquals("azi/abc", AzulaLinks.parse("azula://i?c=$encoded"))
    }

    // --- legacy forms must keep working during the transition ---

    @Test
    fun parsesLegacyWebLink() {
        assertEquals("abc123", AzulaLinks.parse(AzulaLinks.webLink("abc123")))
        assertEquals("abc123", AzulaLinks.parse("https://azula.app/s/abc123"))
    }

    @Test
    fun parsesLegacyConnectPathLink() {
        assertEquals("abc123", AzulaLinks.parse("https://azula.app/connect/abc123"))
    }

    @Test
    fun parsesLegacyCustomScheme() {
        assertEquals("abc123", AzulaLinks.parse("azula://connect?code=abc123"))
    }

    @Test
    fun parsesLegacyCustomSchemeWithPercentEncoding() {
        assertEquals("a b", AzulaLinks.parse("azula://connect?code=a%20b"))
    }

    @Test
    fun returnsNullForUnrecognized() {
        assertNull(AzulaLinks.parse("https://example.com/nope"))
        assertNull(AzulaLinks.parse("not a link"))
    }
}
