package dev.azula.sync

import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.LogEntryCodec
import dev.azula.core.ZERO_PREV_HASH
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip, append/insert-rejection, and vector tests for [FileEventLogStore]
 * against azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
 * "Per-Device Append-Only Signed Logs" -- the same semantics as azula-cli's Rust
 * `sync::LogStore` (see that module's own test suite in
 * `.worktrees/azula-cli--multi-device-identity/src/sync.rs`), on the Kotlin side.
 */
class FileEventLogStoreTest {
    private val tempDir = createTempDirectory("eventlogstore-test").toFile()
    private val ed25519 = FakeEd25519()

    private val deviceASecret = fixedKey(0x40)
    private val deviceAPk = fakePublicKey(deviceASecret)
    private val deviceBSecret = fixedKey(0x80)
    private val deviceBPk = fakePublicKey(deviceBSecret)

    // multi-device-identity task 4.6: every store in this file's existing tests
    // uses this one fixed root -- [otherRootPkHex] is only used by the new
    // root-scoping tests below.
    private val rootPkHex = fakePublicKey(fixedKey(0x01)).toHexLower()
    private val otherRootPkHex = fakePublicKey(fixedKey(0x02)).toHexLower()

    private fun newStore(ed25519: Ed25519 = this.ed25519, rootPkHex: String = this.rootPkHex) =
        FileEventLogStore(tempDir, ed25519) { rootPkHex }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun signedEntry(
        secret: ByteArray,
        pk: ByteArray,
        seq: Long,
        lamport: Long,
        prevHash: ByteArray,
        kind: Int = EventKind.MESSAGE_OUT.wireValue,
        body: ByteArray = "{}".encodeToByteArray(),
    ) = LogEntryCodec.sign(kind, pk, seq, lamport, seq * 1000, prevHash, body, secret, ed25519)

    // -- appendOwn: seq/prev_hash/lamport bookkeeping ----------------------------------------

    @Test
    fun appendOwnChainsSeqAndPrevHashForItsOwnDevice() = runTest {
        val store = newStore()
        val e1 = store.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 1000L, "{}".encodeToByteArray())
        assertEquals(1L, e1.seq)
        assertTrue(e1.prevHash.contentEquals(ZERO_PREV_HASH))
        assertTrue(LogEntryCodec.verifySignature(e1, ed25519))

        val e2 = store.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 2000L, "{}".encodeToByteArray())
        assertEquals(2L, e2.seq)
        assertTrue(e2.prevHash.contentEquals(LogEntryCodec.chainHash(e1)))

        assertEquals(mapOf(deviceAPk.toHexLower() to 2L), store.vector())
    }

    @Test
    fun appendOwnLamportExceedsTheHighestSeenAnywhere() = runTest {
        val store = newStore()
        // A foreign device's entry (synced in from elsewhere) already carries lamport 5.
        val foreign = signedEntry(deviceBSecret, deviceBPk, seq = 1, lamport = 5, prevHash = ZERO_PREV_HASH)
        assertTrue(store.insert(foreign))

        // Our own next append must out-lamport the highest seen anywhere (5), not just
        // our own prior lamport (none so far).
        val own = store.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 1000L, "{}".encodeToByteArray())
        assertEquals(6L, own.lamport)
        val own2 = store.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 2000L, "{}".encodeToByteArray())
        assertEquals(7L, own2.lamport)
    }

    // -- insert: every rejection path leaves the cursor untouched ----------------------------

    @Test
    fun insertRejectsABadSignatureAndDoesNotAdvanceTheCursor() = runTest {
        val store = newStore()
        val e1 = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH)
        val tampered = e1.copy(signature = e1.signature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })

        assertFalse(store.insert(tampered))
        assertTrue(store.vector().isEmpty())
        assertTrue(store.readFrom(deviceAPk, 0).isEmpty())

        // The real (untampered) entry still accepts afterward.
        assertTrue(store.insert(e1))
        assertEquals(mapOf(deviceAPk.toHexLower() to 1L), store.vector())
    }

    @Test
    fun insertRejectsASeqGapAndDoesNotAdvanceTheCursor() = runTest {
        val store = newStore()
        val e1 = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH)
        assertTrue(store.insert(e1))

        // Skips seq 2 entirely.
        val e3 = signedEntry(deviceASecret, deviceAPk, seq = 3, lamport = 3, prevHash = LogEntryCodec.chainHash(e1))
        assertFalse(store.insert(e3))
        assertEquals(mapOf(deviceAPk.toHexLower() to 1L), store.vector())
        assertEquals(1, store.readFrom(deviceAPk, 0).size)
    }

    @Test
    fun insertRejectsABrokenPrevHashAndDoesNotAdvanceTheCursor() = runTest {
        val store = newStore()
        val e1 = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH)
        assertTrue(store.insert(e1))

        val bogusHash = LogEntryCodec.chainHash(e1).also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val broken = signedEntry(deviceASecret, deviceAPk, seq = 2, lamport = 2, prevHash = bogusHash)
        assertFalse(store.insert(broken))
        assertEquals(mapOf(deviceAPk.toHexLower() to 1L), store.vector())

        // The real e2 (correct prev_hash) still accepts after the broken one is rejected.
        val e2 = signedEntry(deviceASecret, deviceAPk, seq = 2, lamport = 2, prevHash = LogEntryCodec.chainHash(e1))
        assertTrue(store.insert(e2))
        assertEquals(mapOf(deviceAPk.toHexLower() to 2L), store.vector())
    }

    @Test
    fun insertRejectsAFirstEntryWithSeqNotOne() = runTest {
        val store = newStore()
        val badFirst = signedEntry(deviceASecret, deviceAPk, seq = 2, lamport = 1, prevHash = ZERO_PREV_HASH)
        assertFalse(store.insert(badFirst))
        assertTrue(store.vector().isEmpty())
    }

    // -- vector(): highest *contiguous* seq, even after a rejected out-of-order attempt ------

    @Test
    fun vectorReflectsOnlyTheHighestContiguousSeqAfterARejectedGapAttempt() = runTest {
        val store = newStore()
        val e1 = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH)
        val e2 = signedEntry(deviceASecret, deviceAPk, seq = 2, lamport = 2, prevHash = LogEntryCodec.chainHash(e1))
        assertTrue(store.insert(e1))
        assertTrue(store.insert(e2))

        // A seq-4 entry (skipping 3) must never advance the reported vector to 4.
        val e4 = signedEntry(deviceASecret, deviceAPk, seq = 4, lamport = 4, prevHash = LogEntryCodec.chainHash(e2))
        assertFalse(store.insert(e4))
        assertEquals(2L, store.vector()[deviceAPk.toHexLower()])

        // The real seq-3 entry (correctly chained) is still accepted, then 4 too.
        val e3 = signedEntry(deviceASecret, deviceAPk, seq = 3, lamport = 3, prevHash = LogEntryCodec.chainHash(e2))
        assertTrue(store.insert(e3))
        assertEquals(3L, store.vector()[deviceAPk.toHexLower()])
        val e4real = signedEntry(deviceASecret, deviceAPk, seq = 4, lamport = 4, prevHash = LogEntryCodec.chainHash(e3))
        assertTrue(store.insert(e4real))
        assertEquals(4L, store.vector()[deviceAPk.toHexLower()])
    }

    // -- Unknown kinds: stored and re-served, verbatim ---------------------------------------

    @Test
    fun unknownKindEntryIsAcceptedStoredAndReServedVerbatim() = runTest {
        val store = newStore()
        val unknown = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH, kind = 0x7F, body = "{\"x\":1}".encodeToByteArray())
        assertTrue(store.insert(unknown))

        val reServed = store.readFrom(deviceAPk, 0)
        assertEquals(1, reServed.size)
        assertEquals(0x7F, reServed[0].kind)
        assertTrue(reServed[0].body.contentEquals(unknown.body))
    }

    // -- readFrom: only entries after the given seq, ascending -------------------------------

    @Test
    fun readFromReturnsOnlyEntriesAfterTheGivenSeq() = runTest {
        val store = newStore()
        var prev = ZERO_PREV_HASH
        for (seq in 1L..5L) {
            val e = signedEntry(deviceASecret, deviceAPk, seq = seq, lamport = seq, prevHash = prev)
            assertTrue(store.insert(e))
            prev = LogEntryCodec.chainHash(e)
        }
        val fromThree = store.readFrom(deviceAPk, 3)
        assertEquals(listOf(4L, 5L), fromThree.map { it.seq })
    }

    // -- Round trip: persists and reopens with correct vector + entries ---------------------

    @Test
    fun persistsAndReopensWithCorrectVectorAndEntries() = runTest {
        var prev = ZERO_PREV_HASH
        run {
            val store = newStore()
            for (seq in 1L..3L) {
                val e = signedEntry(deviceASecret, deviceAPk, seq = seq, lamport = seq, prevHash = prev)
                assertTrue(store.insert(e))
                prev = LogEntryCodec.chainHash(e)
            }
        }

        // A brand-new store instance over the same directory must rebuild identical state.
        val reopened = newStore()
        assertEquals(mapOf(deviceAPk.toHexLower() to 3L), reopened.vector())
        val entries = reopened.readFrom(deviceAPk, 0)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.seq })
    }

    // -- Root-identity namespacing (multi-device-identity task 4.6) -------------------------

    /**
     * The actual bug task 4.6 fixes: the same device key's entries authored under one
     * root must not be visible to -- or overwritable by -- a store opened for a
     * *different* root at the same base directory. Before this task, both roots
     * shared the same flat `eventlog/<device_pk>.jsonl` file, so a device that
     * changed identities would fold its old identity's private history into the new
     * one.
     */
    @Test
    fun twoDifferentRootsOnTheSameDeviceGetSeparateLogSets() = runTest {
        val storeA = newStore(rootPkHex = rootPkHex)
        repeat(3) { i ->
            storeA.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, (i + 1) * 1000L, "{}".encodeToByteArray())
        }

        // A fresh store for a DIFFERENT root at the same base directory must start
        // empty -- it must not see the entries the SAME device key authored under
        // root A.
        val storeB = newStore(rootPkHex = otherRootPkHex)
        assertTrue(storeB.vector().isEmpty(), "root B's vector must not include root A's entries")
        assertTrue(storeB.readFrom(deviceAPk, 0).isEmpty())

        storeB.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 4000L, "{}".encodeToByteArray())
        storeB.appendOwn(deviceAPk, deviceASecret, EventKind.MESSAGE_OUT.wireValue, 5000L, "{}".encodeToByteArray())

        // Reopening root A's store must still show only its own 3 entries -- root
        // B's 2 entries for the identical device key must not appear in its vector.
        val reopenedA = newStore(rootPkHex = rootPkHex)
        assertEquals(3L, reopenedA.vector()[deviceAPk.toHexLower()])
        assertEquals(3, reopenedA.readFrom(deviceAPk, 0).size)

        // On disk, the two roots' logs live in distinct namespaced subdirectories.
        assertTrue(File(tempDir, "eventlog/$rootPkHex/${deviceAPk.toHexLower()}.jsonl").exists())
        assertTrue(File(tempDir, "eventlog/$otherRootPkHex/${deviceAPk.toHexLower()}.jsonl").exists())
    }

    /**
     * task 4.6's migration: a pre-existing flat-layout file (as the 4.4 backfill may
     * have written during this same unreleased change, before this task's
     * namespacing landed) is relocated under the opening root's subdirectory rather
     * than left behind or silently dropped.
     */
    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun preExistingFlatLayoutIsRelocatedUnderTheOpeningRoot() = runTest {
        val eventlogDir = File(tempDir, "eventlog")
        eventlogDir.mkdirs()
        val flatFile = File(eventlogDir, "${deviceAPk.toHexLower()}.jsonl")
        val e1 = signedEntry(deviceASecret, deviceAPk, seq = 1, lamport = 1, prevHash = ZERO_PREV_HASH)
        flatFile.writeText(Base64.encode(LogEntryCodec.encode(e1)) + "\n")

        val store = newStore(rootPkHex = rootPkHex)

        // The flat file's entry is visible through the now-namespaced store...
        assertEquals(mapOf(deviceAPk.toHexLower() to 1L), store.vector())
        // ...the flat file itself is gone...
        assertFalse(flatFile.exists(), "the flat file must be moved, not merely read")
        // ...and its content now lives under the root's namespaced subdirectory.
        assertTrue(File(eventlogDir, "$rootPkHex/${deviceAPk.toHexLower()}.jsonl").exists())
    }
}
