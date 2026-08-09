import dev.azula.link.AzulaLinks
import dev.azula.link.ParsedAzulaLink
import dev.azula.link.isDeviceLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeepLinkTest {
    private val payload = "aziaeaaci2fm6e2xtppnfk3saaaaaaaaabamf5hk3dbfv2gk43ufvsw4zdqn5uw45bnoruwg23foqwwe6lumvzq"

    // A device-link payload vector (multi-device-identity task 6.6) — device_pk = bytes
    // 0x01..0x20, name = "My Laptop", a 32-byte opaque ticket. Same vector as
    // azula-site's links.test.ts/index.test.ts (shape/decode is exercised there; this suite
    // only exercises the deeplink wrapper/parse, not `core`'s `LinkPayloadCodec`).
    private val linkPayload =
        "azlaeaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypsacknpeqeyylqorxxaabamf5hk3dbfv2gk43ufvsw4zdqn5uw45bnoruwg23foqwwe6lumvzq"

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

    // --- retired legacy forms are unrecognized input, not tickets ---

    @Test
    fun rejectsLegacyWebLink() {
        assertNull(AzulaLinks.parse("https://azula.app/s/abc123"))
    }

    @Test
    fun rejectsLegacyConnectPathLink() {
        assertNull(AzulaLinks.parse("https://azula.app/connect/abc123"))
    }

    @Test
    fun rejectsLegacyCustomScheme() {
        assertNull(AzulaLinks.parse("azula://connect?code=abc123"))
        assertNull(AzulaLinks.parse("azula://connect?code=a%20b"))
    }

    @Test
    fun returnsNullForUnrecognized() {
        assertNull(AzulaLinks.parse("https://example.com/nope"))
        assertNull(AzulaLinks.parse("not a link"))
    }

    // --- device-link forms (multi-device-identity task 6.6) ---

    @Test
    fun buildsDeviceLinkUrl() {
        assertEquals("https://azula.app/l/$linkPayload", AzulaLinks.deviceLinkUrl(linkPayload))
    }

    @Test
    fun parsesDeviceLinkWebLink() {
        assertEquals(linkPayload, AzulaLinks.parseDeviceLink(AzulaLinks.deviceLinkUrl(linkPayload)))
    }

    @Test
    fun parsesDeviceLinkCustomScheme() {
        assertEquals(linkPayload, AzulaLinks.parseDeviceLink("azula://l?c=$linkPayload"))
    }

    @Test
    fun parsesBareDeviceLinkString() {
        assertEquals(linkPayload, AzulaLinks.parseDeviceLink(linkPayload))
        assertEquals(linkPayload, AzulaLinks.parseDeviceLink("  $linkPayload  "))
    }

    @Test
    fun percentDecodesDeviceLinkCustomScheme() {
        val encoded = "azl%2Fabc"
        assertEquals("azl/abc", AzulaLinks.parseDeviceLink("azula://l?c=$encoded"))
    }

    @Test
    fun deviceLinkParseDoesNotRecognizeConnectForms() {
        // A device-link payload must never be mistaken for a dialable connect ticket
        // (and vice versa) -- the two flows are entirely separate.
        assertNull(AzulaLinks.parseDeviceLink(payload)) // an invite payload
        assertNull(AzulaLinks.parseDeviceLink(AzulaLinks.inviteLink(payload)))
        assertNull(AzulaLinks.parseDeviceLink("https://azula.app/s/abc123"))
        assertNull(AzulaLinks.parse(linkPayload)) // a device-link payload
        assertNull(AzulaLinks.parse(AzulaLinks.deviceLinkUrl(linkPayload)))
        assertNull(AzulaLinks.parse("azula://l?c=$linkPayload"))
    }

    @Test
    fun deviceLinkReturnsNullForUnrecognized() {
        assertNull(AzulaLinks.parseDeviceLink("https://example.com/nope"))
        assertNull(AzulaLinks.parseDeviceLink("not a link"))
    }

    @Test
    fun isDeviceLinkRecognizesPrefix() {
        assertEquals(true, isDeviceLink(linkPayload))
        assertEquals(true, isDeviceLink("  $linkPayload  "))
        assertEquals(false, isDeviceLink("https://azula.app/s/abc"))
        assertEquals(false, isDeviceLink(payload)) // an invite payload
    }

    // --- tagged parse (multi-device-identity task 6.6) ---

    @Test
    fun parseTaggedRecognizesConnectForms() {
        val connect = assertIs<ParsedAzulaLink.Connect>(AzulaLinks.parseTagged(AzulaLinks.inviteLink(payload)))
        assertEquals(payload, connect.token)
        val scheme = assertIs<ParsedAzulaLink.Connect>(AzulaLinks.parseTagged("azula://i?c=$payload"))
        assertEquals(payload, scheme.token)
        // A retired legacy form is neither a Connect nor a DeviceLink.
        assertNull(AzulaLinks.parseTagged("azula://connect?code=abc123"))
    }

    @Test
    fun parseTaggedRecognizesDeviceLinkForms() {
        val webLink = assertIs<ParsedAzulaLink.DeviceLink>(AzulaLinks.parseTagged(AzulaLinks.deviceLinkUrl(linkPayload)))
        assertEquals(linkPayload, webLink.payload)
        val scheme = assertIs<ParsedAzulaLink.DeviceLink>(AzulaLinks.parseTagged("azula://l?c=$linkPayload"))
        assertEquals(linkPayload, scheme.payload)
        val bare = assertIs<ParsedAzulaLink.DeviceLink>(AzulaLinks.parseTagged(linkPayload))
        assertEquals(linkPayload, bare.payload)
    }

    @Test
    fun parseTaggedReturnsNullForUnrecognized() {
        assertNull(AzulaLinks.parseTagged("https://example.com/nope"))
        assertNull(AzulaLinks.parseTagged("not a link"))
    }
}
