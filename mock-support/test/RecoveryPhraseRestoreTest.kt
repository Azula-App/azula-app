@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.core.RecoveryPhrase
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.IncomingConnection
import dev.azula.net.IrohTransport
import dev.azula.net.P2pStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the restore path the Settings UI drives: [AzulaState.exportRecoveryPhrase] /
 * [AzulaState.importRecoveryPhrase] (backed by [dev.azula.state.ConnectService]), retargeted
 * (multi-device-identity task 3.3) to the identity's root secret rather than the device's
 * node key -- see [RootSecretTransport] for why none of this ever touches the node id.
 * Task 3.4 reworks restore into enrollment (self-cert + `device_add`, tested against a real
 * event log in `AccountSyncWiringTest.kt` instead -- these tests here don't wire one), and
 * tasks 3.5a-c add the second-confirmation/peer-ticket/rebind behavior for the one
 * genuinely destructive case: restoring a phrase for a *different* identity than whatever
 * root secret this device already holds.
 */
class RecoveryPhraseRestoreTest {

    @Test
    fun exportedPhraseMatchesTheTransportsRootSecret() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val phrase = azulaState.exportRecoveryPhrase()
        assertNotNull(phrase, "a bound transport should always have a recovery phrase")
        assertEquals(24, phrase.trim().split(" ").size)
        assertTrue(transport.rootSecretBytes()!!.contentEquals(RecoveryPhrase.decode(phrase)!!))
    }

    @Test
    fun importingAnInvalidPhraseFailsWithoutTouchingIdentity() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()
        val nodeIdBefore = azulaState.nodeId
        val rootBefore = transport.rootSecretBytes()

        val ok = azulaState.importRecoveryPhrase("not a real recovery phrase at all")
        runCurrent()

        assertFalse(ok, "a malformed phrase must be rejected")
        assertEquals(nodeIdBefore, azulaState.nodeId, "a rejected phrase must not touch the node id")
        assertEquals(rootBefore?.toList(), transport.rootSecretBytes()?.toList(), "a rejected phrase must not touch the root secret")
    }

    @Test
    fun importingAValidPhraseSetsTheRootSecretWithoutTouchingNodeId() = runTest {
        val transport = RootSecretTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()
        val nodeIdBefore = azulaState.nodeId

        val key = ByteArray(32) { (it * 7).toByte() }
        val phrase = RecoveryPhrase.encode(key)

        val ok = azulaState.importRecoveryPhrase(phrase)
        runCurrent()

        assertTrue(ok, "a well-formed, checksum-valid phrase must be accepted")
        assertEquals(nodeIdBefore, azulaState.nodeId, "importing a phrase must not change the node id")
        assertTrue(key.contentEquals(transport.rootSecretBytes()!!), "the decoded phrase must become the root secret")
    }

    @Test
    fun aDeviceWithANodeKeyAndNoRootSecretIsAValidState() = runTest {
        // A QR-linked device: it has its own node id but holds no root secret until (if
        // ever) it's given root authority -- see the `identity` capability's "QR-linked
        // device stores no root secret" scenario.
        val transport = RootSecretTransport(root = null)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        assertNotNull(azulaState.nodeId, "a node id exists independent of root-secret possession")
        assertNull(azulaState.exportRecoveryPhrase(), "no root secret means no recovery phrase to show")
    }

    @Test
    fun aStoreFailureOnImportDoesNotPropagateOutOfRestore() = runTest {
        val transport = RootSecretTransport(failImport = true)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val key = ByteArray(32) { (it * 7).toByte() }
        val phrase = RecoveryPhrase.encode(key)

        // Both callers (the setup step and the Settings dialog) run this inside a
        // `rememberCoroutineScope().launch`, which has no CoroutineExceptionHandler -- so
        // anything escaping here terminates the process rather than showing an error.
        val ok = azulaState.importRecoveryPhrase(phrase)
        runCurrent()

        // True, not false: the phrase decoded fine -- it's the store write that failed.
        // Reporting false would surface "that isn't a valid recovery phrase" for a phrase
        // that was perfectly valid.
        assertTrue(ok, "a valid phrase must still report success when only the store write failed")
    }

    // ---- multi-device-identity tasks 3.5a/b/c: the recovery-restore-ux decisions ----

    @Test
    fun sameIdentityRestoreIsNotFlaggedAsDifferent() = runTest {
        val root = ByteArray(32) { 9 }
        val transport = RootSecretTransport(root = root)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        // Re-encoding the exact bytes already held -- "re-entering your own phrase".
        assertEquals(false, azulaState.isDifferentIdentityRestore(RecoveryPhrase.encode(root)))
    }

    @Test
    fun aDifferentIdentityRestoreIsFlaggedAsDestructive() = runTest {
        val transport = RootSecretTransport(root = ByteArray(32) { 9 })
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val otherRoot = ByteArray(32) { (it * 5 + 3).toByte() }
        assertEquals(true, azulaState.isDifferentIdentityRestore(RecoveryPhrase.encode(otherRoot)))
    }

    @Test
    fun aFreshDeviceWithNoHeldIdentityIsNeverFlaggedAsDestructive() = runTest {
        // No root secret held yet -- a fresh install / QR-linked device. Nothing to
        // discard, so this must stay single-tap regardless of which phrase is pasted.
        val transport = RootSecretTransport(root = null)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val anyRoot = ByteArray(32) { (it * 5 + 3).toByte() }
        assertEquals(false, azulaState.isDifferentIdentityRestore(RecoveryPhrase.encode(anyRoot)))
    }

    @Test
    fun anInvalidPhraseIsNeitherSameNorDifferent() = runTest {
        val transport = RootSecretTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        assertNull(azulaState.isDifferentIdentityRestore("not a real recovery phrase at all"))
    }

    @Test
    fun restoringADifferentIdentityClearsSavedPeerTicketsAndRebinds() = runTest {
        val transport = RootSecretTransport(root = ByteArray(32) { 9 })
        val peerStore = InMemoryPeerStore()
        peerStore.save(listOf("some-old-ticket"))
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore)
        azulaState.start()
        runCurrent()

        val otherRoot = ByteArray(32) { (it * 5 + 3).toByte() }
        val ok = azulaState.importRecoveryPhrase(RecoveryPhrase.encode(otherRoot))
        runCurrent()

        assertTrue(ok)
        assertTrue(peerStore.load().isEmpty(), "the displaced identity's saved peer tickets must be cleared (task 3.5c)")
        assertEquals(1, transport.rebindCount, "a different-identity restore must attempt to begin syncing under the new identity")
    }

    @Test
    fun restoringTheSameIdentityLeavesPeerTicketsAndTransportUntouched() = runTest {
        val root = ByteArray(32) { 9 }
        val transport = RootSecretTransport(root = root)
        val peerStore = InMemoryPeerStore()
        peerStore.save(listOf("some-existing-ticket"))
        val azulaState = testAzulaState(backgroundScope, transport, peerStore = peerStore)
        azulaState.start()
        runCurrent()

        val ok = azulaState.importRecoveryPhrase(RecoveryPhrase.encode(root))
        runCurrent()

        assertTrue(ok)
        assertEquals(
            listOf("some-existing-ticket"),
            peerStore.load(),
            "a same-identity restore must change nothing observable -- its saved tickets stay valid (task 3.5c)",
        )
        assertEquals(0, transport.rebindCount, "a same-identity restore must not disrupt the transport")
    }

    @Test
    fun aFailedRebindOnADifferentIdentityRestoreStillCommitsTheEnrollment() = runTest {
        val transport = RootSecretTransport(root = ByteArray(32) { 9 }, failRebind = true)
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val otherRoot = ByteArray(32) { (it * 5 + 3).toByte() }
        val ok = azulaState.importRecoveryPhrase(RecoveryPhrase.encode(otherRoot))
        runCurrent()

        // Spec: "A failed transport re-bind SHALL NOT fail the restore -- the enrollment
        // is committed locally and the transport degrades to offline exactly as a failed
        // initial bind does."
        assertTrue(ok, "a failed rebind must not fail the restore")
        assertTrue(otherRoot.contentEquals(transport.rootSecretBytes()), "the root secret must still be committed locally")
        assertEquals(1, transport.rebindCount, "a rebind must still have been attempted")
    }
}

/**
 * A transport that actually models the root/node split, which [FakeTransport] does not (it
 * mirrors one fixed fake key as both). [root] seeds the initial root-secret state (null
 * models a QR-linked device); [importRootSecret] mutates it in place with no effect on
 * [nodeId] -- exactly the multi-device-identity task 3.3 behavior. [failImport]
 * simulates a key-store write that throws (a locked Keychain, an unrecoverable corrupt
 * keyset, ...). [failRebind] simulates [rebind] failing (network unavailable) -- task
 * 3.4/3.5a's "a failed transport re-bind SHALL NOT fail the restore"; [rebindCount] lets a
 * test assert exactly when a rebind was (or wasn't) attempted, per task 3.5c ("a
 * same-identity restore ... must change nothing observable").
 */
private class RootSecretTransport(
    root: ByteArray? = ByteArray(32) { 9 },
    private val failImport: Boolean = false,
    private val failRebind: Boolean = false,
) : IrohTransport {
    override var nodeId: String? = "self"
    override var onCameOnline: (() -> Unit)? = null

    private var root: ByteArray? = root

    var rebindCount: Int = 0
        private set

    override suspend fun bind(alpns: List<String>) {
        onCameOnline?.invoke()
    }

    override fun rootSecretBytes(): ByteArray? = root

    override fun importRootSecret(bytes: ByteArray) {
        if (failImport) throw RuntimeException("key store unavailable")
        root = bytes
    }

    // A deterministic (non-real) "public key" -- SHA-256 of the secret -- is all
    // isDifferentIdentityRestore/ensureSelfCertified need to derive something distinct
    // per secret; these tests never verify a real Ed25519 signature.
    override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)

    override suspend fun rebind() {
        rebindCount++
        if (failRebind) throw RuntimeException("rebind failed")
    }

    override fun incoming(): Flow<IncomingConnection> = emptyFlow()

    override suspend fun myTicket(): String = "ticket-$nodeId"
    override fun secretKeyBytes(): ByteArray = ByteArray(32) { 1 }
    override suspend fun importSecretKey(bytes: ByteArray) { /* not exercised here */ }
    override fun peerIdForTicket(ticket: String): String? = null
    override suspend fun connect(ticket: String, alpn: String): P2pStream =
        throw RuntimeException("this transport dials nothing")
    override suspend fun shutdown() = Unit
}
