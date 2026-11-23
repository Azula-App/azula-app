import dev.azula.vmhost.ProxyRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProxyRequestTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun connectWithPort() {
        val t = ProxyRequest.parse(b("CONNECT deb.debian.org:443 HTTP/1.1\r\nHost: deb.debian.org:443\r\n\r\n"))
        assertTrue(t.connect)
        assertEquals("deb.debian.org", t.host)
        assertEquals(443, t.port)
        assertEquals("CONNECT", t.method)
    }

    @Test fun connectDefaultsTo443() {
        val t = ProxyRequest.parse(b("CONNECT example.com HTTP/1.1\r\n\r\n"))
        assertEquals(443, t.port)
    }

    @Test fun connectIpv6IsUnbracketed() {
        val t = ProxyRequest.parse(b("CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n\r\n"))
        assertEquals("2001:db8::1", t.host)
        assertEquals(8443, t.port)
    }

    @Test fun connectMethodIsCaseInsensitive() {
        assertTrue(ProxyRequest.parse(b("connect a.b:1 HTTP/1.1\r\n\r\n")).connect)
    }

    @Test fun absoluteFormHttpDefaultsTo80() {
        val t = ProxyRequest.parse(b("GET http://example.com/path?q=1 HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        assertTrue(!t.connect)
        assertEquals("example.com", t.host)
        assertEquals(80, t.port)
        assertEquals("GET", t.method)
    }

    @Test fun absoluteFormHttpsAndExplicitPort() {
        assertEquals(443, ProxyRequest.parse(b("GET https://example.com/ HTTP/1.1\r\n\r\n")).port)
        assertEquals(8080, ProxyRequest.parse(b("POST http://example.com:8080/x HTTP/1.1\r\n\r\n")).port)
    }

    @Test fun originFormIsRejected() {
        val e = assertFailsWith<IllegalArgumentException> { ProxyRequest.parse(b("GET /index.html HTTP/1.1\r\nHost: x\r\n\r\n")) }
        assertTrue("not absolute-form" in e.message!!)
    }

    @Test fun malformedRequests() {
        assertFailsWith<IllegalArgumentException> { ProxyRequest.parse(b("GARBAGE\r\n\r\n")) }
        assertFailsWith<IllegalArgumentException> { ProxyRequest.parse(b("CONNECT a:notaport HTTP/1.1\r\n\r\n")) }
        assertFailsWith<IllegalArgumentException> { ProxyRequest.parse(b("CONNECT a:70000 HTTP/1.1\r\n\r\n")) }
        assertFailsWith<IllegalArgumentException> { ProxyRequest.parse(b("CONNECT a:443 HTTP/1.1\r\n")) } // no blank line yet
    }

    @Test fun headEndMarksOverReadBytes() {
        val head = "CONNECT a.b:443 HTTP/1.1\r\n\r\n"
        val t = ProxyRequest.parse(b(head + "\u0016\u0003\u0001"))
        assertEquals(head.length, t.headEnd)
    }

    @Test fun indexOfHeadEnd() {
        assertEquals(-1, ProxyRequest.indexOfHeadEnd(b("abc\r\n\r")))
        assertEquals(3, ProxyRequest.indexOfHeadEnd(b("abc\r\n\r\nxyz")))
    }
}
