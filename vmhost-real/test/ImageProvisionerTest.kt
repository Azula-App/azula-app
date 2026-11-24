import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.azula.vmhost.CancelledException
import dev.azula.vmhost.HttpFetch
import dev.azula.vmhost.ImageDownloader
import dev.azula.vmhost.ImageProvisioner
import dev.azula.vmhost.ProvisionFailure
import dev.azula.vmhost.ProvisionStep
import dev.azula.vmhost.ProvisionState
import dev.azula.vmhost.Sha512Sums
import dev.azula.vmhost.Xz
import dev.azula.vmhost.XzCodec
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    @Volatile private var archive: ByteArray = archiveWith(LZMA2Options(1))
    private val sums get() = "${Sha512Sums.hex(Sha512Sums.newDigest().digest(archive))}  $name\n"

    private fun archiveWith(options: LZMA2Options): ByteArray {
        val tar = ByteArrayOutputStream()
        TarFixtures.file(tar, "README", "hi".toByteArray())
        TarFixtures.sparse(tar, "disk.raw", 1_000_000, listOf(0L to seg1, 700_000L to seg2))
        TarFixtures.end(tar)
        val out = ByteArrayOutputStream()
        // In 64 KiB pieces: this encoder's uncompressed mode garbles writes that cross a chunk.
        XZOutputStream(out, options).use { x -> tar.toByteArray().asList().chunked(65536).forEach { x.write(it.toByteArray()) } }
        return out.toByteArray()
    }

    private class Req(val method: String, val path: String, val range: String?)

    private val requests = CopyOnWriteArrayList<Req>()
    /** Per GET of the archive: the body to send (corrupt it, cut it), by attempt number from 1. */
    @Volatile var cutAfter: (Int) -> Int = { -1 }
    @Volatile var corrupt: (Int) -> Boolean = { false }
    @Volatile var failAll = false
    @Volatile var ignoreRange = false
    @Volatile var sumsServed: String? = null
    /** Per GET of the archive: called once its bytes up to `holdAfter` are sent, before the rest. */
    @Volatile var holdAfter = -1
    @Volatile var hold: () -> Unit = {}
    /** Per GET of the archive: called before answering. */
    @Volatile var beforeAnswer: (Int) -> Unit = {}
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
                path == "/SHA512SUMS" -> { val b = (sumsServed ?: sums).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                path == "/$name" && ex.requestMethod == "HEAD" -> {
                    ex.responseHeaders.add("Last-Modified", "Mon, 16 Nov 2026 16:46:25 GMT")
                    ex.responseHeaders.add("Content-Length", archive.size.toString())
                    ex.sendResponseHeaders(200, -1)
                }
                path == "/$name" -> {
                    val n = synchronized(this) { ++gets }
                    beforeAnswer(n)
                    var body = archive
                    var start = 0
                    ex.requestHeaders.getFirst("Range")?.takeUnless { ignoreRange }?.let { r ->
                        start = r.removePrefix("bytes=").removeSuffix("-").toInt()
                        ex.responseHeaders.add("Content-Range", "bytes $start-${archive.size - 1}/${archive.size}")
                    }
                    body = body.copyOfRange(start, body.size)
                    if (corrupt(n)) body = body.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                    ex.responseHeaders.add("Last-Modified", "Mon, 16 Nov 2026 16:46:25 GMT")
                    ex.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
                    val cut = cutAfter(n)
                    ex.responseBody.use { out ->
                        if (cut in 0 until body.size) { out.write(body, 0, cut); out.flush(); ex.close() }
                        else if (holdAfter in 0 until body.size) {
                            out.write(body, 0, holdAfter); out.flush()
                            hold()
                            out.write(body, holdAfter, body.size - holdAfter)
                        } else out.write(body)
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
        allocated: (File) -> Long = { 0 }, xz: Xz = Xz(), onStep: (ProvisionStep) -> Unit = {},
    ) = ImageProvisioner(
        dir = d, freeBytes = { free }, stageSeed = { run.seeds++; File(d, "seed.iso").writeText("seed") },
        listener = { step, _, _ -> if (run.steps.lastOrNull() != step) { run.steps += step; onStep(step) } },
        cancelled = cancelled,
        downloader = ImageDownloader(HttpFetch(httpsOnly = false)), http = HttpFetch(httpsOnly = false),
        baseUrl = base, archiveName = name, sleep = {}, log = { run.log += it }, allocatedBytes = allocated, xz = xz,
    )

    private val streamed = "unpacked while downloading"
    private fun Run.logged(text: String) = log.any { text in it }

    private fun assertNothingLeft(d: File) {
        for (f in listOf(name, "$name.part", "disk.raw", "disk.raw.part")) assertFalse(File(d, f).exists(), "$f is left")
        assertNoUnpackThread()
    }

    private fun assertNoUnpackThread() =
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "vmhost-unpack" }, "the unpack thread outlived the run")

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!cond()) { assertTrue(System.nanoTime() < end, "timed out waiting for $what"); Thread.sleep(5) }
    }

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
        assertTrue(run.logged(streamed), run.log.toString())
        assertNoUnpackThread()
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

    @Test fun theDiskIsWrittenWhileTheDownloadIsStillRunning() {
        val d = dir(); val run = Run()
        val go = CountDownLatch(1)
        holdAfter = archive.size - 60_000
        hold = { go.await(20, TimeUnit.SECONDS) }
        val t = thread { provisioner(d, run).run() }
        val diskPart = File(d, "disk.raw.part")
        try {
            // The whole first segment is there, at its place, with the end of the archive not even sent.
            waitFor("the first segment") { diskPart.length() >= seg1.size }
            assertContentEquals(seg1, diskPart.inputStream().use { it.readNBytes(seg1.size) })
            assertFalse(File(d, name).exists())
            assertFalse(File(d, "disk.raw").exists(), "an unverified disk isn't in place")
            assertEquals(listOf(ProvisionStep.DOWNLOADING), run.steps)
        } finally { go.countDown(); t.join() }
        assertDiskIsTheImage(d)
        assertTrue(run.logged(streamed), run.log.toString())
    }

    @Test fun theDiskIsNotInPlaceUntilTheChecksumMatched() {
        val d = dir(); val run = Run()
        val p = provisioner(d, run, onStep = { step ->
            if (step == ProvisionStep.VERIFYING || step == ProvisionStep.EXTRACTING) {
                assertFalse(File(d, "disk.raw").exists(), "disk in place at $step")
            }
        })
        p.run()
        assertDiskIsTheImage(d)
    }

    @Test fun aChecksumMismatchWipesADiskThatUnpackedFine() {
        val d = dir(); val run = Run()
        sumsServed = "${"0".repeat(128)}  $name\n" // the archive decodes; it just isn't the published one
        val e = assertFailsWith<ProvisionFailure> { provisioner(d, run).run() }
        assertTrue("corrupt" in e.message!!)
        assertEquals(3, gets().size)
        assertNothingLeft(d)
        assertFalse(provisioner(d).load().imageReady)
        assertFalse(ProvisionStep.EXTRACTING in run.steps)
        sumsServed = null
        provisioner(d).run()
        assertDiskIsTheImage(d)
    }

    @Test fun aRestartUnpacksThePrefixAgain() {
        val d = dir(); val run = Run()
        // Process death leaves both: the .part, and a disk that is as far along as it happened to be.
        var seen = 0L
        assertFailsWith<CancelledException> {
            ImageProvisioner(
                dir = d, freeBytes = { 20 * gib }, stageSeed = {}, listener = { s, done, _ -> if (s == ProvisionStep.DOWNLOADING) seen = done },
                cancelled = { seen >= 200_000 }, downloader = ImageDownloader(HttpFetch(false)), http = HttpFetch(false),
                baseUrl = base, archiveName = name, sleep = {},
            ).run()
        }
        val have = File(d, "$name.part").length()
        File(d, "disk.raw.part").writeBytes(Random(9).nextBytes(400_000))
        provisioner(d, run).run()
        assertEquals(listOf(null, "bytes=$have-"), gets().map { it.range }, "resumed, not restarted")
        assertTrue(run.logged(streamed), run.log.toString())
        assertDiskIsTheImage(d)
    }

    @Test fun aRestartUnpacksThePrefixBeforeTheServerAnswers() {
        val d = dir(); val run = Run()
        cutAfter = { n -> if (n == 1) 250_000 else 0 }
        assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        cutAfter = { -1 }
        beforeAnswer = { waitFor("the prefix to unpack") { File(d, "disk.raw.part").length() >= seg1.size } }
        provisioner(d, run).run()
        assertEquals("bytes=250000-", gets().last().range)
        assertTrue(run.logged(streamed), run.log.toString())
        assertDiskIsTheImage(d)
    }

    @Test fun aStalePrefixNeverReachesTheDisk() {
        // A .part that isn't quite the archive's beginning, and a server that answers with the whole file. What's
        // on disk at the end is the archive, but the unpack alongside read some of what was there before.
        val stored = LZMA2Options(1).apply { mode = LZMA2Options.MODE_UNCOMPRESSED }
        for (n in listOf(100_000, 250_000)) for ((options, why) in listOf(stored to "it read other bytes", LZMA2Options(1) to "CorruptedInputException")) {
            val d = dir(); val run = Run()
            archive = archiveWith(options)
            cutAfter = { 0 }
            ignoreRange = false
            beforeAnswer = {}
            assertFailsWith<ProvisionFailure> { provisioner(d).run() }
            File(d, "$name.part").writeBytes(archive.copyOf(n).also { it[50_000]++ })
            cutAfter = { -1 }
            ignoreRange = true
            beforeAnswer = { Thread.sleep(300) }
            provisioner(d, run).run()
            assertTrue(run.logged(why), run.log.toString())
            assertTrue(run.logged("unpacked the archive"), run.log.toString())
            assertDiskIsTheImage(d)
            assertNoUnpackThread()
        }
    }

    @Test fun cancellingMidDownloadStopsTheUnpackAndKeepsOnlyThePart() {
        val d = dir()
        var seen = 0L
        val p = ImageProvisioner(
            dir = d, freeBytes = { 20 * gib }, stageSeed = {}, listener = { s, done, _ -> if (s == ProvisionStep.DOWNLOADING) seen = done },
            cancelled = { seen >= 200_000 }, downloader = ImageDownloader(HttpFetch(false)), http = HttpFetch(false),
            baseUrl = base, archiveName = name, sleep = {},
        )
        assertFailsWith<CancelledException> { p.run() }
        assertNoUnpackThread()
        assertFalse(File(d, "disk.raw.part").exists())
        assertTrue(File(d, "$name.part").length() >= 200_000)
    }

    @Test fun givingUpOnTheNetworkStopsTheUnpackAndKeepsOnlyThePart() {
        val d = dir()
        cutAfter = { n -> if (n == 1) 200_000 else 0 }
        assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        assertNoUnpackThread()
        assertFalse(File(d, "disk.raw.part").exists())
        assertEquals(200_000, File(d, "$name.part").length())
    }

    @Test fun stepsPersistedByTheVersionBeforeStreamingResume() {
        for (step in listOf(ProvisionStep.VERIFYING, ProvisionStep.EXTRACTING)) {
            val d = dir(); val run = Run()
            // Its provision.json, its finished archive, and a disk it was killed halfway through.
            File(d, "provision.json").writeText(
                """{"step":"$step","archiveBytes":${archive.size},"sha512":"${sums.substring(0, 128)}","validator":"Mon, 16 Nov 2026 16:46:25 GMT",""" +
                    """"diskGb":5,"imageReady":false,"firstBootDone":false,"corruptDownloads":0}""",
            )
            File(d, name).writeBytes(archive)
            File(d, "disk.raw.part").writeBytes(Random(3).nextBytes(500_000))
            provisioner(d, run).run()
            assertEquals(0, gets().size)
            assertTrue(run.logged("unpacked the archive"), run.log.toString())
            assertDiskIsTheImage(d)
        }
    }

    @Test fun aDownloadHalfDoneByTheVersionBeforeStreamingResumes() {
        val d = dir(); val run = Run()
        File(d, "provision.json").writeText(
            """{"step":"DOWNLOADING","archiveBytes":${archive.size},"sha512":"${sums.substring(0, 128)}","validator":"Mon, 16 Nov 2026 16:46:25 GMT",""" +
                """"diskGb":5,"imageReady":false,"firstBootDone":false,"corruptDownloads":0}""",
        )
        File(d, "$name.part").writeBytes(archive.copyOf(220_000))
        provisioner(d, run).run()
        assertEquals(listOf("bytes=220000-"), gets().map { it.range })
        assertTrue(run.logged(streamed), run.log.toString())
        assertDiskIsTheImage(d)
    }

    @Test fun anUnpackThatFailsAlongsideTheDownloadFallsBackToTheArchive() {
        val d = dir(); val run = Run()
        var asked = 0
        // The first time the decoder is opened is on the unpack thread.
        val xz = Xz { if (Thread.currentThread().name == "vmhost-unpack" && asked++ == 0) throw IllegalStateException("no decoder today") else null }
        provisioner(d, run, xz = xz).run()
        assertTrue(run.logged("no decoder today"), run.log.toString())
        assertTrue(run.logged("unpacked the archive"), run.log.toString())
        assertDiskIsTheImage(d)
        assertNoUnpackThread()
    }

    @Test fun aNativeDecoderThatFailsIsReplacedByTheJavaOne() {
        val d = dir(); val run = Run()
        var destroyed = 0
        var created = 0L
        val broken = object : XzCodec {
            override fun create(threads: Int, memLimit: Long) = ++created
            override fun decode(handle: Long, input: ByteArray, inOff: Int, inLen: Int, output: ByteArray, outOff: Int, outLen: Int, finish: Boolean) = -2L
            override fun destroy(handle: Long) { destroyed++ }
        }
        provisioner(d, run, xz = Xz { broken }).run()
        assertTrue(run.logged("native xz"), run.log.toString())
        assertTrue(run.logged("using the java decoder"), run.log.toString())
        assertEquals(2, created, "once alongside the download, once on the archive")
        assertEquals(2, destroyed)
        assertDiskIsTheImage(d)
    }

    @Test fun whatIsAlreadyUnpackedIsNotNeededAgain() {
        val d = dir()
        val need = 4 * gib + archive.size
        val unpacked = { f: File -> if (f.name == "disk.raw.part") gib else 0L }
        assertFailsWith<ProvisionFailure> { provisioner(d, free = need - gib).run() }
        assertEquals(gib, provisioner(d, allocated = unpacked).footprintBytes())
        provisioner(d, free = need - gib, allocated = unpacked).run()
        assertDiskIsTheImage2(d)
    }

    @Test fun theStateFileIsUnchangedByStreaming() {
        // Nothing about the unpack alongside is persisted, so either version reads the other's state.
        val d = dir()
        cutAfter = { 0 }
        assertFailsWith<ProvisionFailure> { provisioner(d).run() }
        val st = provisioner(d).load()
        assertEquals(ProvisionState(archiveBytes = archive.size.toLong(), sha512 = sums.substring(0, 128), validator = st.validator, diskGb = 5), st)
        assertEquals(
            setOf("step", "archiveBytes", "sha512", "validator", "diskGb", "imageReady", "firstBootDone", "corruptDownloads"),
            Regex("\"(\\w+)\":").findAll(File(d, "provision.json").readText()).map { it.groupValues[1] }.toSet(),
        )
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
