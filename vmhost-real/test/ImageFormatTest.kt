import dev.azula.vmhost.Backoff
import dev.azula.vmhost.FileSparseSink
import dev.azula.vmhost.Resume
import dev.azula.vmhost.Sha512Sums
import dev.azula.vmhost.TarException
import dev.azula.vmhost.TarReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageFormatTest {
    private val h1 = "a".repeat(128)
    private val h2 = "0123456789abcdef".repeat(8)

    @Test fun parsesSha512Sums() {
        val text = "$h1  debian-13-azure-arm64.tar.xz\n$h2  debian-13-generic-arm64.tar.xz\n\n# comment\nnot a line\n"
        assertEquals(h2, Sha512Sums.expected(text, "debian-13-generic-arm64.tar.xz"))
        assertEquals(h1, Sha512Sums.expected(text, "debian-13-azure-arm64.tar.xz"))
        assertNull(Sha512Sums.expected(text, "debian-13-nocloud-arm64.tar.xz"))
    }

    @Test fun sha512SumsToleratesBinaryMarkerUppercaseAndCrlf() {
        val text = "${h2.uppercase()} *debian-13-generic-arm64.tar.xz\r\n"
        assertEquals(h2, Sha512Sums.expected(text, "debian-13-generic-arm64.tar.xz"))
    }

    @Test fun sha512SumsRejectsShortOrNonHexHashes() {
        assertNull(Sha512Sums.expected("abc123  debian-13-generic-arm64.tar.xz", "debian-13-generic-arm64.tar.xz"))
        assertNull(Sha512Sums.expected("${"z".repeat(128)}  debian-13-generic-arm64.tar.xz", "debian-13-generic-arm64.tar.xz"))
    }

    @Test fun resumeStartsWhereThePartEnds() {
        assertEquals(0, Resume.startOffset(0, 300))
        assertEquals(120, Resume.startOffset(120, 300))
        assertEquals(300, Resume.startOffset(300, 300))
    }

    @Test fun resumeRestartsFromZeroWhenThePartCantBelongToThisFile() {
        assertEquals(0, Resume.startOffset(301, 300))
        assertEquals(0, Resume.startOffset(50, 0))
        assertEquals(0, Resume.startOffset(-1, 300))
    }

    @Test fun parsesContentRange() {
        assertEquals(Triple(100L, 299L, 300L), Resume.parseContentRange("bytes 100-299/300"))
        assertNull(Resume.parseContentRange("bytes */300"))
        assertNull(Resume.parseContentRange(null))
    }

    @Test fun backoffGrowsThenCaps() {
        assertEquals(listOf(2_000L, 2_000L, 6_000L, 18_000L, 18_000L), (0..4).map { Backoff.delayMs(it) })
        assertEquals(3, Backoff.MAX_FAILURES)
    }

    private fun tmp(): File = Files.createTempDirectory("tar").toFile()

    @Test fun readsPlainFilesAndSkipsToTheOneWanted() {
        val out = ByteArrayOutputStream()
        TarFixtures.file(out, "README", ByteArray(700) { 1 })
        TarFixtures.file(out, "disk.raw", "hello disk".toByteArray())
        TarFixtures.end(out)
        val r = TarReader(ByteArrayInputStream(out.toByteArray()))
        val a = assertNotNull(r.next())
        assertEquals("README", a.name)
        assertEquals(700, a.dataSize)
        val b = assertNotNull(r.next()) // skipped README's data and padding
        assertEquals("disk.raw", b.name)
        val f = File(tmp(), "d")
        RandomAccessFile(f, "rw").use { r.copyData(b, FileSparseSink(it)) }
        assertEquals("hello disk", f.readText())
        assertNull(r.next())
    }

    @Test fun unpacksGnuSparseMembersLeavingHoles() {
        val d1 = ByteArray(1000) { (it % 251).toByte() }
        val d2 = ByteArray(600) { 7 }
        val out = ByteArrayOutputStream()
        TarFixtures.sparse(out, "disk.raw", real = 10_000, segments = listOf(0L to d1, 5_000L to d2))
        TarFixtures.end(out)
        val r = TarReader(ByteArrayInputStream(out.toByteArray()))
        val e = assertNotNull(r.next())
        assertEquals('S', e.type)
        assertEquals(1600, e.dataSize)
        assertEquals(10_000, e.realSize)
        val f = File(tmp(), "disk.raw")
        val seen = ArrayList<Long>()
        RandomAccessFile(f, "rw").use { r.copyData(e, FileSparseSink(it)) { n -> seen += n } }
        assertEquals(1600L, seen.last())
        val got = f.readBytes()
        assertEquals(10_000, got.size)
        assertContentEquals(d1, got.copyOfRange(0, 1000))
        assertContentEquals(ByteArray(4000), got.copyOfRange(1000, 5000)) // the hole
        assertContentEquals(d2, got.copyOfRange(5000, 5600))
        assertContentEquals(ByteArray(4400), got.copyOfRange(5600, 10_000))
    }

    @Test fun readsSparseMapsLongerThanTheFirstHeader() {
        // 30 segments: 4 in the header, 21 + 5 in two extension blocks.
        val segs = (0 until 30).map { (it * 100L) to ByteArray(10) { b -> (it + b).toByte() } }
        val out = ByteArrayOutputStream()
        TarFixtures.sparse(out, "disk.raw", real = 3_000, segments = segs)
        TarFixtures.file(out, "after", "x".toByteArray())
        TarFixtures.end(out)
        val r = TarReader(ByteArrayInputStream(out.toByteArray()))
        val e = assertNotNull(r.next())
        assertEquals(30, e.segments.size)
        val f = File(tmp(), "x")
        RandomAccessFile(f, "rw").use { r.copyData(e, FileSparseSink(it)) }
        val got = f.readBytes()
        assertEquals(3_000, got.size)
        assertContentEquals(segs[29].second, got.copyOfRange(2900, 2910))
        assertEquals("after", assertNotNull(r.next()).name)
    }

    @Test fun honoursGnuLongNamesAndPaxPaths() {
        val out = ByteArrayOutputStream()
        val long = "some/very/long/" + "x".repeat(150) + "/disk.raw"
        TarFixtures.longName(out, long, data = "a".toByteArray())
        TarFixtures.pax(out, "pax/path/disk.raw")
        TarFixtures.file(out, "ignored", "b".toByteArray())
        TarFixtures.end(out)
        val r = TarReader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(long, assertNotNull(r.next()).name)
        assertEquals("pax/path/disk.raw", assertNotNull(r.next()).name)
    }

    @Test fun rejectsACorruptHeaderChecksum() {
        val out = ByteArrayOutputStream()
        TarFixtures.file(out, "disk.raw", "z".toByteArray())
        val bytes = out.toByteArray()
        bytes[3] = (bytes[3] + 1).toByte() // change the name after the checksum was sealed
        assertFailsWith<TarException> { TarReader(ByteArrayInputStream(bytes)).next() }
    }

    @Test fun anEmptyStreamIsTheEnd() {
        assertNull(TarReader(ByteArrayInputStream(ByteArray(0))).next())
    }

    @Test fun aTruncatedMemberFailsInsteadOfReturningShortData() {
        val out = ByteArrayOutputStream()
        TarFixtures.file(out, "disk.raw", ByteArray(2000))
        val cut = out.toByteArray().copyOf(512 + 900)
        val r = TarReader(ByteArrayInputStream(cut))
        val e = assertNotNull(r.next())
        assertFailsWith<java.io.EOFException> { RandomAccessFile(File(tmp(), "t"), "rw").use { r.copyData(e, FileSparseSink(it)) } }
    }

    @Test fun readsBase256Sizes() {
        val b = ByteArray(12)
        b[0] = 0x80.toByte(); b[10] = 0x01; b[11] = 0x00
        assertEquals(256, TarReader.numeric(b, 0, 12))
    }
}
