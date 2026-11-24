import dev.azula.vmhost.CancelledException
import dev.azula.vmhost.Sha512Sums
import dev.azula.vmhost.StreamingUnpack
import dev.azula.vmhost.TailingInputStream
import dev.azula.vmhost.Xz
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The unpack that runs alongside the download: the stream it follows, and the whole of it on a growing file. */
class StreamingUnpackTest {
    private val dir = Files.createTempDirectory("unpack").toFile()
    private val file = File(dir, "a.part")
    private val dest = File(dir, "disk.part")
    private val exec = Executors.newCachedThreadPool()

    @AfterTest fun clean() { exec.shutdownNow(); dir.deleteRecursively() }

    private fun append(b: ByteArray) = FileOutputStream(file, true).use { it.write(b) }
    private fun <T> async(f: () -> T): Future<T> = exec.submit<T>(f)
    private fun <T> Future<T>.soon(): T = get(20, TimeUnit.SECONDS)
    private fun <T> Future<T>.assertBlocked() { assertFailsWith<TimeoutException> { get(150, TimeUnit.MILLISECONDS) } }
    private fun sha512(b: ByteArray) = Sha512Sums.hex(Sha512Sums.newDigest().digest(b))

    private val data = Random(5).nextBytes(10_000)

    @Test fun theTailBlocksForBytesThatAreNotThereYet() {
        file.createNewFile()
        TailingInputStream(file, data.size.toLong()).use { t ->
            val read = async { t.readNBytes(data.size) }
            read.assertBlocked()
            append(data.copyOf(4000)); t.grew(4000)
            read.assertBlocked()
            append(data.copyOfRange(4000, data.size)); t.grew(data.size.toLong())
            assertContentEquals(data, read.soon())
            assertEquals(-1, t.read())
            assertEquals(sha512(data), t.drainAndHash())
        }
    }

    @Test fun theTailGivesWhatIsThereWithoutWaitingForAFullBuffer() {
        append(data.copyOf(100))
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(100)
            assertEquals(100, t.read(ByteArray(5000)))
        }
    }

    @Test fun theTailNeverReadsPastWhatWasReported() {
        append(data)
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(10)
            assertEquals(10, t.read(ByteArray(5000)))
            async { t.read(ByteArray(5000)) }.assertBlocked()
            t.cancel()
        }
    }

    @Test fun theTailFollowsTheFileThroughItsRename() {
        append(data.copyOf(6000))
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(6000)
            assertEquals(6000, t.readNBytes(6000).size)
            append(data.copyOfRange(6000, data.size))
            assertTrue(file.renameTo(File(dir, "a")))
            t.grew(data.size.toLong())
            assertEquals(sha512(data), t.drainAndHash())
        }
    }

    @Test fun cancellingUnblocksTheReader() {
        file.createNewFile()
        TailingInputStream(file, 10).use { t ->
            val read = async { t.read() }
            read.assertBlocked()
            t.cancel()
            assertIs<CancelledException>(assertFailsWith<ExecutionException> { read.soon() }.cause)
            assertFailsWith<CancelledException> { t.read() }
        }
    }

    @Test fun aDownloadThatStartsOverIsWaitedFor() {
        append(data.copyOf(5000))
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(9000) // more than there is: cut off since
            assertEquals(5000, t.readNBytes(5000).size)
            val rest = async { t.readNBytes(data.size) }
            rest.assertBlocked()
            FileOutputStream(file, false).use { it.write(data.copyOf(3000)) }
            t.grew(3000)
            rest.assertBlocked()
            append(data.copyOfRange(3000, data.size)); t.grew(data.size.toLong())
            assertContentEquals(data.copyOfRange(5000, data.size), rest.soon())
            assertEquals(sha512(data), t.drainAndHash())
        }
    }

    @Test fun theTailHashesWhatItReadNotWhatTheFileEndsUpAs() {
        val stale = data.copyOf().also { it[7]++ }
        append(stale.copyOf(5000))
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(5000)
            assertEquals(5000, t.readNBytes(5000).size)
            FileOutputStream(file, false).use { it.write(data) }
            t.grew(data.size.toLong())
            val got = t.drainAndHash()
            assertContentEquals(data, file.readBytes())
            assertEquals(sha512(stale), got)
            assertTrue(got != sha512(data))
        }
    }

    @Test fun aCompleteFileThatShrinksIsAnErrorNotAWait() {
        append(data.copyOf(100))
        TailingInputStream(file, data.size.toLong()).use { t ->
            t.grew(data.size.toLong())
            assertFailsWith<EOFException> { async { t.drainAndHash() }.let { f -> try { f.soon() } catch (e: ExecutionException) { throw e.cause!! } } }
        }
    }

    // ---- the whole of it ----

    private val seg1 = Random(1).nextBytes(300_000)
    private val seg2 = ByteArray(200_000) { (it / 1000).toByte() }
    private fun archive(diskName: String = "disk.raw"): ByteArray {
        val tar = ByteArrayOutputStream()
        TarFixtures.file(tar, "README", "hi".toByteArray())
        TarFixtures.sparse(tar, diskName, 2_000_000, listOf(65_536L to seg1, 1_500_000L to seg2))
        TarFixtures.file(tar, "after", Random(2).nextBytes(50_000))
        TarFixtures.end(tar)
        return ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options(1)).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun unpack(total: Int) = StreamingUnpack(file, total.toLong(), dest, Xz()::open).also { it.start() }

    private fun assertDisk() = RandomAccessFile(dest, "r").use { r ->
        assertEquals(2_000_000, r.length())
        val want = ByteArray(2_000_000)
        seg1.copyInto(want, 65_536); seg2.copyInto(want, 1_500_000)
        assertContentEquals(want, ByteArray(want.size).also { r.readFully(it) })
    }

    @Test fun aSparseDiskUnpacksFromAFileThatGrowsInDribs() {
        val xz = archive()
        file.createNewFile()
        val u = unpack(xz.size)
        val rnd = Random(4)
        var at = 0
        while (at < xz.size) {
            val n = minOf(rnd.nextInt(1, 9000), xz.size - at)
            append(xz.copyOfRange(at, at + n))
            at += n
            u.grew(at.toLong())
        }
        val polls = ArrayList<Pair<Long, Long>>()
        val r = async { u.await { d, s -> polls += d to s } }.soon()
        assertIs<StreamingUnpack.Outcome.Unpacked>(r)
        assertEquals(sha512(xz), r.sha512, "everything went by, past the disk to the end of the file")
        assertEquals(500_000, r.stats.bytes)
        assertEquals(500_000L to 500_000L, polls.last())
        assertDisk()
    }

    @Test fun aDiskWrittenInFullStillWaitsForTheLastBytesOfTheArchive() {
        val xz = archive()
        append(xz.copyOf(xz.size - 1))
        val u = unpack(xz.size)
        u.grew(xz.size - 1L)
        val done = async { u.await { _, _ -> } }
        done.assertBlocked()
        append(xz.copyOfRange(xz.size - 1, xz.size)); u.grew(xz.size.toLong())
        assertEquals(sha512(xz), assertIs<StreamingUnpack.Outcome.Unpacked>(done.soon()).sha512)
        assertDisk()
    }

    @Test fun aStaleDiskIsReplacedNotMixedIn() {
        dest.writeBytes(Random(8).nextBytes(3_000_000))
        val xz = archive()
        append(xz)
        val u = unpack(xz.size)
        u.grew(xz.size.toLong())
        assertIs<StreamingUnpack.Outcome.Unpacked>(async { u.await { _, _ -> } }.soon())
        assertDisk()
    }

    @Test fun brokenArchivesFailAndLeaveNoDisk() {
        val xz = archive()
        val cases = listOf(
            xz.copyOf().also { it[20]++ },
            Random(3).nextBytes(5000),
            archive("other.raw"),
            xz.copyOf(xz.size / 2),
        )
        for ((i, bad) in cases.withIndex()) {
            file.delete(); append(bad)
            val u = unpack(bad.size)
            u.grew(bad.size.toLong())
            assertIs<StreamingUnpack.Outcome.Failed>(async { u.await { _, _ -> } }.soon(), "case $i")
            assertFalse(dest.exists(), "case $i")
        }
    }

    @Test fun cancellingStopsItAndDeletesTheDisk() {
        val xz = archive()
        append(xz.copyOf(xz.size - 10_000))
        val u = unpack(xz.size)
        u.grew(xz.size - 10_000L)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (dest.length() < 65_536 + seg1.size) { assertTrue(System.nanoTime() < end); Thread.sleep(5) }
        async { u.cancel() }.soon()
        assertFalse(dest.exists())
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "vmhost-unpack" })
    }

    @Test fun aPollThatThrowsEndsTheWaitNotTheUnpack() {
        val xz = archive()
        append(xz.copyOf(xz.size - 10_000))
        val u = unpack(xz.size)
        u.grew(xz.size - 10_000L)
        assertFailsWith<CancelledException> { u.await { _, _ -> throw CancelledException() } }
        append(xz.copyOfRange(xz.size - 10_000, xz.size)); u.grew(xz.size.toLong())
        assertIs<StreamingUnpack.Outcome.Unpacked>(async { u.await { _, _ -> } }.soon())
        assertDisk()
    }

    @Test fun cancellingAfterItFinishedDeletesTheDiskToo() {
        val xz = archive()
        append(xz)
        val u = unpack(xz.size)
        u.grew(xz.size.toLong())
        async { u.await { _, _ -> } }.soon()
        assertTrue(dest.exists())
        u.cancel(); u.cancel()
        assertFalse(dest.exists())
    }
}
