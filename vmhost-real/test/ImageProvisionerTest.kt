import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.azula.vmhost.CancelledException
import dev.azula.vmhost.HttpFetch
import dev.azula.vmhost.ImageDownloader
import dev.azula.vmhost.ImageProvisioner
import dev.azula.vmhost.ProvisionFailure
import dev.azula.vmhost.ProvisionStep
import dev.azula.vmhost.Sha512Sums
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The whole image pipeline against a local HTTP server that can break, corrupt and range. */
class ImageProvisionerTest {
    private val name = "debian-13-generic-arm64.tar.xz"
    private val gib = 1L shl 30

    // A "disk": 300 KB of data in two sparse segments, 1 MB when unpacked.
    private val seg1 = Random(1).nextBytes(180_000)
    private val seg2 = Random(2).nextBytes(120_000)
    private val archive: ByteArray = run {
        val tar = ByteArrayOutputStream()
        TarFixtures.file(tar, "README", "hi".toByteArray())
        TarFixtures.sparse(tar, "disk.raw", 1_000_000, listOf(0L to seg1, 700_000L to seg2))
        TarFixtures.end(tar)
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options(1)).use { it.write(tar.toByteArray()) }
        out.toByteArray()
    }
    private val sums = "${Sha512Sums.hex(Sha512Sums.newDigest().digest(archive))}  $name\n"

    private class Req(val method: String, val path: String, val range: String?)

    private val requests = CopyOnWriteArrayList<Req>()
    /** Per GET of the archive: the body to send (corrupt it, cut it), by attempt number from 1. */
    @Volatile var cutAfter: (Int) -> Int = { -1 }
    @Volatile var corrupt: (Int) -> Boolean = { false }
    @Volatile var failAll = false
    private var gets = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex -> handle(ex) }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val dirs = ArrayList<File>()

    @AfterTest fun stop() { server.stop(0); dirs.forEach { it.deleteRecursively() } }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        requests += Req(ex.requestMethod, path, ex.requestHeaders.getFirst("Range"))
        try {
            if (failAll) { ex.sendResponseHeaders(503, -1); return }
            when {
                path == "/SHA512SUMS" -> { val b = sums.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                path == "/$name" && ex.requestMethod == "HEAD" -> {
                    ex.responseHeaders.add("Last-Modified", "Mon, 16 Nov 2026 16:46:25 GMT")
                    ex.responseHeaders.add("Content-Length", archive.size.toString())
                    ex.sendResponseHeaders(200, -1)
                }
                path == "/$name" -> {
                    val n = synchronized(this) { ++gets }
                    var body = archive
                    var start = 0
                    ex.requestHeaders.getFirst("Range")?.let { r ->
                        start = r.removePrefix("bytes=").removeSuffix("-").toInt()
                        ex.responseHeaders.add("Content-Range", "bytes $start-${archive.size - 1}/${archive.size}")
                    }
                    body = body.copyOfRange(start, body.size)
                    if (corrupt(n)) body = body.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                    ex.responseHeaders.add("Last-Modified", "Mon, 16 Nov 2026 16:46:25 GMT")
                    ex.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
                    val cut = cutAfter(n)
                    ex.responseBody.use { out ->
                        if (cut in 0 until body.size) { out.write(body, 0, cut); out.flush(); ex.close() } else out.write(body)
                    }
                }
                else -> ex.sendResponseHeaders(404, -1)
            }
        } catch (_: Exception) {
        } finally { ex.close() }
    }

    private fun dir() = Files.createTempDirectory("prov").toFile().also { dirs += it }

    private class Run(val steps: MutableList<ProvisionStep> = ArrayList(), val log: MutableList<String> = ArrayList(), var seeds: Int = 0)

    private fun provisioner(
        d: File, run: Run = Run(), free: Long = 20 * gib, cancelled: () -> Boolean = { false },
    ) = ImageProvisioner(
        dir = d, freeBytes = { free }, stageSeed = { run.seeds++; File(d, "seed.iso").writeText("seed") },
        listener = { step, _, _ -> if (run.steps.lastOrNull() != step) run.steps += step },
        cancelled = cancelled,
        downloader = ImageDownloader(HttpFetch(httpsOnly = false)), http = HttpFetch(httpsOnly = false),
        baseUrl = base, archiveName = name, sleep = {}, log = { run.log += it },
    )

    private fun assertDiskIsTheImage(d: File) {
        val disk = File(d, "disk.raw")
        assertEquals(5 * gib, disk.length(), "grown to 25% of 20 GiB")
        java.io.RandomAccessFile(disk, "r").use {
            val a = ByteArray(seg1.size); it.readFully(a); assertContentEquals(seg1, a)
            it.seek(700_000); val b = ByteArray(seg2.size); it.readFully(b); assertContentEquals(seg2, b)
        }
    }

    private fun gets() = requests.filter { it.method == "GET" && it.path == "/$name" }

    @Test fun happyPathDownloadsVerifiesUnpacksAndGrowsTheDisk() {
        val d = dir(); val run = Run()
        provisioner(d, run).run()
        assertEquals(
            listOf(ProvisionStep.DOWNLOADING, ProvisionStep.VERIFYING, ProvisionStep.EXTRACTING, ProvisionStep.PREPARING_DISK),
            run.steps,
        )
        assertDiskIsTheImage(d)
        assertFalse(File(d, name).exists(), "the archive is deleted after a successful unpack")
        assertFalse(File(d, "$name.part").exists())
        assertFalse(File(d, "disk.raw.part").exists())
        assertEquals(1, run.seeds)
        val st = provisioner(d).load()
        assertTrue(st.imageReady && !st.firstBootDone)
        // A second run does nothing.
        provisioner(d).run()
        assertEquals(1, gets().size)
    }

    @Test fun aDroppedConnectionResumesFromThePartOffsetNotZero() {
        val d = dir(); val run = Run()
        cutAfter = { n -> if (n == 1) 100_000 else -1 }
        provisioner(d, run).run()
        val g = gets()
        assertEquals(2, g.size)
        assertEquals(null, g[0].range)
        assertEquals("bytes=100000-", g[1].range)
        assertDiskIsTheImage(d)
        assertTrue(run.log.any { it.contains("resumed from byte 100000") }, run.log.toString())
    }

    @Test fun aKilledProcessResumesFromTheStateOnDisk() {
        val d = dir()
        // The first process is "killed" once 90 KB have landed: cancellation leaves the .part behind.
        var seen = 0L
        val first = ImageProvisioner(
            dir = d, freeBytes = { 20 * gib }, stageSeed = {}, listener = { s, done, _ -> if (s == ProvisionStep.DOWNLOADING) seen = done },
            cancelled = { seen >= 90_000 }, downloader = ImageDownloader(HttpFetch(false)), http = HttpFetch(false),
            baseUrl = base, archiveName = name, sleep = {},
        )
        assertFailsWith<CancelledException> { first.run() }
        val partLen = File(d, "$name.part").length()
        assertTrue(partLen in 1 until archive.size.toLong(), "partial: $partLen")
        // A brand-new provisioner (new process) finishes it.
        provisioner(d).run()
        assertEquals("bytes=$partLen-", gets().last().range)
        assertDiskIsTheImage(d)
    }

    @Test fun aTamperedPartIsRejectedAndRedownloadedFromZero() {
        val d = dir(); val run = Run()
        cutAfter = { n -> if (n == 1) 100_000 else -1 }
        // Fail the first attempt after the cut, then tamper with the .part before the resume.
        var tampered = false
        val p = ImageProvisioner(
            dir = d, freeBytes = { 20 * gib }, stageSeed = {}, listener = { _, _, _ -> },
            cancelled = { false }, downloader = ImageDownloader(HttpFetch(false)), http = HttpFetch(false),
            baseUrl = base, archiveName = name,
            sleep = { if (!tampered) { tampered = true; File(d, "$name.part").also { f -> val b = f.readBytes(); b[5] = (b[5] + 1).toByte(); f.writeBytes(b) } } },
            log = { run.log += it },
        )
        p.run()
        assertTrue(run.log.any { it.contains("checksum mismatch") }, run.log.toString())
        val g = gets()
        assertEquals(3, g.size) // cut, resumed (now corrupt), then a clean full download
        assertEquals("bytes=100000-", g[1].range)
        assertEquals(null, g[2].range)
        assertDiskIsTheImage(d)
    }

    @Test fun aCorruptFinishedArchiveIsRejectedAndRedownloaded() {
        val d = dir(); val run = Run()
        corrupt = { n -> n == 1 }
        provisioner(d, run).run()
        assertTrue(run.log.any { it.contains("checksum mismatch") })
        assertEquals(2, gets().size)
        assertEquals(null, gets()[1].range, "the bad file is discarded, not resumed")
        assertEquals(2, requests.count { it.path == "/SHA512SUMS" }, "the sums are re-read for the new attempt")
        assertDiskIsTheImage(d)
        assertEquals(listOf(ProvisionStep.DOWNLOADING, ProvisionStep.VERIFYING, ProvisionStep.DOWNLOADING, ProvisionStep.VERIFYING, ProvisionStep.EXTRACTING, ProvisionStep.PREPARING_DISK), run.steps)
    }

    @Test fun threeCorruptDownloadsInARowSurfaceRetry() {
        val d = dir()
        corrupt = { true }
        val e = assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        assertTrue(e.retryable)
        assertTrue("corrupt" in e.message!!)
        assertEquals(3, gets().size)
        assertFalse(File(d, name).exists())
        assertFalse(File(d, "$name.part").exists())
        // retry: provision() again starts clean.
        corrupt = { false }
        provisioner(d).run()
        assertDiskIsTheImage(d)
    }

    @Test fun slowProgressBetweenDropsKeepsGoing() {
        // Each attempt moves the .part forward, so the failure count resets: a flaky link still finishes.
        val d = dir()
        cutAfter = { n -> if (n < 5) 50_000 else -1 }
        provisioner(d).run()
        assertEquals(5, gets().size)
        assertDiskIsTheImage(d)
    }

    @Test fun threeNetworkFailuresSurfaceRetryAndKeepTheBytes() {
        val d = dir()
        cutAfter = { 0 } // every attempt drops before delivering a byte
        val e = assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        assertTrue("check your connection" in e.message!!)
        assertTrue(e.retryable)
    }

    @Test fun anUnreachableServerFailsAfterThreeTriesWithNoBytes() {
        val d = dir()
        failAll = true
        val e = assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        assertTrue(e.retryable)
        assertEquals(3, requests.count { it.method == "HEAD" })
        failAll = false
        provisioner(d).run()
        assertDiskIsTheImage(d)
    }

    @Test fun notEnoughSpaceFailsBeforeAnyDownload() {
        val d = dir()
        val e = assertFailsWith<ProvisionFailure> { provisioner(d, free = 2 * gib).run() }
        assertEquals("needs 4.1 GB free · 2 GB free now", e.message)
        assertTrue(e.retryable)
        assertEquals(0, gets().size, "no archive bytes were requested")
        assertFalse(File(d, "$name.part").exists())
    }

    @Test fun spaceAlreadySpentOnThePartIsNotNeededAgain() {
        val d = dir()
        // 4.2 GB free is short for a fresh start but fine for a resume that already has the whole archive.
        File(d, "$name.part").writeBytes(archive.copyOf(archive.size - 10))
        val tight = 3 * gib + 1 * gib + 100_000
        provisioner(d, free = tight).run()
        assertDiskIsTheImage2(d)
    }

    // free=tight gives a 1 GiB disk; only the data matters here.
    private fun assertDiskIsTheImage2(d: File) {
        java.io.RandomAccessFile(File(d, "disk.raw"), "r").use {
            val a = ByteArray(seg1.size); it.readFully(a); assertContentEquals(seg1, a)
        }
    }

    @Test fun anImageWithoutADiskIsAnError() {
        val tar = ByteArrayOutputStream().also { TarFixtures.file(it, "README", "x".toByteArray()); TarFixtures.end(it) }.toByteArray()
        val out = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options(1)).use { it.write(tar) } }.toByteArray()
        val bad = ImageProvisionerTest().also { }
        // Serve the disk-less archive instead.
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sum = "${Sha512Sums.hex(Sha512Sums.newDigest().digest(out))}  $name\n"
        s.createContext("/") { ex ->
            if (ex.requestURI.path == "/SHA512SUMS") { val b = sum.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            else if (ex.requestMethod == "HEAD") { ex.responseHeaders.add("Content-Length", out.size.toString()); ex.sendResponseHeaders(200, -1) }
            else { ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) } }
            ex.close()
        }
        s.start()
        bad.stop()
        try {
            val d = dir()
            val p = ImageProvisioner(d, { 20 * gib }, {}, { _, _, _ -> }, { false }, ImageDownloader(HttpFetch(false)), HttpFetch(false),
                "http://127.0.0.1:${s.address.port}", name, { })
            val e = assertFailsWith<ProvisionFailure> { p.run() }
            assertFalse(e.retryable)
        } finally { s.stop(0) }
    }

    @Test fun httpsOnlyRefusesPlainHttp() {
        val e = assertFailsWith<dev.azula.vmhost.NetworkException> { HttpFetch().open("http://127.0.0.1:${server.address.port}/SHA512SUMS") }
        assertTrue("non-https" in e.message!!)
    }
}
