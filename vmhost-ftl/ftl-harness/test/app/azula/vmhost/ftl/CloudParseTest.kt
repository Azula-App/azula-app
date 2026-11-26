package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudParseTest {
    private val sha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    @Test fun digestCountsAndHashes() {
        assertEquals(Digest(4, sha), Digest.of("test".byteInputStream()))
        assertEquals(Digest(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"), Digest.of("".byteInputStream()))
        // Longer than the read buffer.
        val big = ByteArray(200_000) { 7 }
        assertEquals(200_000L, Digest.of(big.inputStream()).bytes)
        assertEquals(Digest.of(big.inputStream()), Digest.of(big.inputStream()))
    }

    @Test fun digestOfSeqIsWhatSeqPrints() {
        assertEquals(Digest.of("1\n2\n3\n".byteInputStream()), Digest.ofSeq(3))
        // What `seq 1 300000` gives `wc -c` and `sha256sum` elsewhere.
        assertEquals(Digest(1_988_895, "a036031249164ec858e23450a91585ae7dcb73d481105832ca33813da893233f"), Digest.ofSeq(300_000))
    }

    @Test fun sha256sumLine() {
        assertEquals(sha, Digest.fromSha256sum("$sha  e2e/blob.bin\n"))
        assertEquals(sha, Digest.fromSha256sum("$sha *e2e/blob.bin"))
        assertNull(Digest.fromSha256sum(""))
        assertNull(Digest.fromSha256sum("sha256sum: e2e/blob.bin: No such file or directory"))
        assertNull(Digest.fromSha256sum("${sha.dropLast(1)}  e2e/blob.bin"))
        assertNull(Digest.fromSha256sum("$sha  a\n$sha  b\n"))
    }

    private fun elf(cls: Int = 2, order: Int = 1, machine: Int = 183) = ByteArray(64).also {
        "\u007fELF".toByteArray(Charsets.ISO_8859_1).copyInto(it)
        it[4] = cls.toByte()
        it[5] = order.toByte()
        it[18] = machine.toByte()
        it[19] = (machine shr 8).toByte()
    }

    @Test fun elfHeader() {
        assertNull(Elf.notAarch64(elf()))
        assertNotNull(Elf.notAarch64(ByteArray(0)))
        assertNotNull(Elf.notAarch64(elf().copyOf(19)))
        assertEquals("not an ELF file", Elf.notAarch64("#!/bin/sh\necho hello from x on aarch64".toByteArray()))
        assertEquals("ELF class 1, not 64-bit", Elf.notAarch64(elf(cls = 1, machine = 40)))
        assertEquals("ELF byte order 2, not little-endian", Elf.notAarch64(elf(order = 2)))
        assertEquals("ELF machine 62, not 183 (aarch64)", Elf.notAarch64(elf(machine = 62)))
        // The low byte alone isn't the machine.
        assertEquals("ELF machine 439, not 183 (aarch64)", Elf.notAarch64(elf(machine = 183 + 256)))
    }

    @Test fun jobRefs() {
        assertEquals("j_0f3a", JobRef.id("default/j_0f3a"))
        assertNull(JobRef.id("j_0f3a"))
        assertNull(JobRef.id("default/j_"))
        assertNull(JobRef.id("default/j_0f3a\nmore"))
        assertNull(JobRef.id("azula: unauthorized: unauthorized"))
        assertNull(JobRef.id(""))
    }

    @Test fun refOfTheJobWithALabel() {
        val json = """[{"argv":["sh","-c","echo \"label\":\"e2e-survivor\""],"created":1.5,"exit_code":null,"job":"j_1","label":"e2e-detach","ref":"default/j_1"},
            {"argv":[],"created":2,"exit_code":0,"job":"j_2","label":"e2e-survivor","ref":"default/j_2"},
            {"job":"j_3","label":"e2e-survivor","ref":"default/j_3"}]"""
        assertEquals("default/j_2", Jobs.refByLabel(json, "e2e-survivor"))
        assertNull(Jobs.refByLabel(json, "e2e-other"))
        assertNull(Jobs.refByLabel("[]", "e2e-survivor"))
        assertNull(Jobs.refByLabel("""[{"job":"j_4"}]""", "e2e-survivor"))
        assertNull("a job without a ref", Jobs.refByLabel("""[{"label":"e2e-survivor"}]""", "e2e-survivor"))
        assertNull("a ref that isn't text", Jobs.refByLabel("""[{"label":"e2e-survivor","ref":7}]""", "e2e-survivor"))
    }

    @Test fun jobsThatArentAnArrayOfJobs() {
        for (bad in listOf("", "no jobs on default", "{}", "null", "[", "[{]", """[{"label":"e2e-survivor","ref":"x"}""")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Jobs.refByLabel(bad, "e2e-survivor") }
        }
    }

    @Test fun noticesOfADroppedConnection() {
        assertTrue(Notice.reattached("azula: connection lost, reattaching to default/j_1\n"))
        assertFalse(Notice.reattached("azula: connection lost, reconnecting\n"))
        assertFalse(Notice.reattached(""))
        assertTrue(Notice.resumed("azula: connection lost, resuming push at 2.9 MiB\nazula: pushed 8.6 MiB", "push"))
        assertFalse("a pull's notice isn't a push's", Notice.resumed("azula: connection lost, resuming pull at 2.9 MiB", "push"))
        assertFalse("not a fault in this run", Notice.resumed("azula: pushed 8.6 MiB → x sha256 y (resumed from 2.9 MiB)", "push"))
        assertFalse(Notice.resumed("", "pull"))
    }

    private val throttle = "2026-11-24T15:38:06.094100Z  WARN endpoint{id=4cf786e150}:relay-actor: iroh::socket::transports::relay::actor: " +
        "The relay is rate-limiting this endpoint; outbound relay traffic is being throttled. "

    @Test fun theRelaysThrottleWarning() {
        assertTrue(Notice.throttled("azula: job default/j_1 started\n$throttle\nazula: pushed 8.6 MiB\n"))
        assertFalse(Notice.throttled("azula: connection lost, resuming push at 2.9 MiB\n"))
        assertFalse(Notice.throttled(""))
    }

    @Test fun whatAzulaSaidWithoutItsLog() {
        assertEquals(
            "azula: could not connect to the host: timed out",
            Notice.withoutLog("2026-11-24T15:37:46Z  WARN azula::endpoint: system TLS roots unavailable\n$throttle\nazula: could not connect to the host: timed out"),
        )
        assertEquals("", Notice.withoutLog(throttle))
        assertEquals("2026-11-24 WARN isn't a log line", Notice.withoutLog("2026-11-24 WARN isn't a log line"))
    }

    @Test fun jsonValues() {
        assertEquals(
            mapOf("a" to listOf(1.0, -2.5e3, true, false, null), "b" to mapOf("c" to "x"), "d" to emptyList<Any>(), "e" to emptyMap<String, Any>()),
            Json.parse(""" { "a" : [1, -2.5e3, true, false, null], "b": {"c":"x"}, "d":[ ], "e":{ } } """),
        )
        assertEquals("q\"b\\s/n\nt\tué\r\b\u000c", Json.parse(""""q\"b\\s\/n\nt\tu\u00e9\r\b\f""""))
        assertEquals("[]{},:", Json.parse(""""[]{},:""""))
    }

    @Test fun jsonThatIsnt() {
        for (bad in listOf("", "  ", "{", "[1,", "[1,]", "[1 2]", "{\"a\"}", "{\"a\":}", "{a:1}", "\"x", "\"\\", "\"\\x\"", "\"\\u12g4\"", "\"\\u12", "tru", "nul", "-", "1 2", "[]x", "'x'")) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { Json.parse(bad) }
        }
    }
}
