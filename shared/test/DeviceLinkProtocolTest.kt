import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.LogEntryCodec
import dev.azula.core.ROLE_MAILBOX
import dev.azula.core.Sha256
import dev.azula.core.VerificationWords
import dev.azula.core.ZERO_PREV_HASH
import dev.azula.net.Contact
import dev.azula.net.IdentityBundle
import dev.azula.net.P2pStream
import dev.azula.state.DeviceLinkProtocol
import dev.azula.sync.ContactKeyBody
import dev.azula.sync.DeviceAddBody
import dev.azula.sync.DeviceRevokeBody
import dev.azula.sync.encodeContactKeyBody
import dev.azula.sync.encodeDeviceAddBody
import dev.azula.sync.encodeDeviceRevokeBody
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.experimental.xor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `azula/link/0` protocol tests (multi-device-identity tasks 6.1/6.2), over an in-memory
 * transport with no real sockets -- mirroring the Rust reference's own
 * (`azula-cli/src/link.rs`) `tokio::io::duplex`-backed `wire_pair()` tests, which is
 * exactly why those are meaningful and why these are too: [DeviceLinkProtocol]'s functions
 * only ever see a [P2pStream], never iroh.
 */
class DeviceLinkProtocolTest {

    // -- deterministic test-only Ed25519 (never real crypto; see core/test's FakeEd25519,
    // which this mirrors but can't import -- see its "internal declarations" caveat) -------

    private class FakeEd25519 : Ed25519 {
        override fun sign(secret: ByteArray, data: ByteArray): ByteArray {
            require(secret.size == 32) { "fake secret must be 32 bytes" }
            return secret + Sha256.hash(secret + data)
        }

        override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != 64) return false
            val secret = signature.copyOfRange(0, 32)
            val mac = signature.copyOfRange(32, 64)
            if (!Sha256.hash(secret).contentEquals(publicKey)) return false
            return Sha256.hash(secret + data).contentEquals(mac)
        }
    }

    private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
    private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }
    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private val ed25519 = FakeEd25519()

    // -- a plain in-memory bidirectional P2pStream pair, mirroring link.rs's wire_pair() ---

    private class QueueStream(private val inbox: Channel<String>, private val outbox: Channel<String>) : P2pStream {
        override suspend fun sendLine(line: String) {
            outbox.send(line)
        }

        override suspend fun receiveLine(): String? = inbox.receiveCatching().getOrNull()
        override suspend fun sendBytes(bytes: ByteArray) = Unit
        override suspend fun receiveBytes(n: Int): ByteArray? = null

        // Closes only this end's outbox -- the direction the *peer* reads from -- mirroring
        // link.rs's `drop(other_writer)` (which drops only one side's writer, producing a
        // clean EOF on the other side's next read while its own still-open writer keeps
        // working). Closing `inbox` too would make a still-in-flight `sendLine` on *this*
        // stream throw ClosedSendChannelException instead of the graceful "closed" outcome
        // these tests assert -- Kotlin's Channel.send (unlike Rust's writer) throws on a
        // closed channel rather than silently no-opping.
        override suspend fun close() {
            outbox.close()
        }
    }

    /** Two ends of one bidirectional pipe: whatever [Pair.first] sends, [Pair.second]
     *  receives, and vice versa -- exactly `link.rs`'s `wire_pair()`. */
    private fun streamPair(): Pair<P2pStream, P2pStream> {
        val aToB = Channel<String>(Channel.UNLIMITED)
        val bToA = Channel<String>(Channel.UNLIMITED)
        return QueueStream(inbox = bToA, outbox = aToB) to QueueStream(inbox = aToB, outbox = bToA)
    }

    private fun sampleBundle(rootPkHex: String = "root-hex"): IdentityBundle = IdentityBundle(
        rootPk = rootPkHex,
        certs = emptyList(),
        revocations = emptyList(),
        contacts = listOf(Contact(rootPk = "contact-root", name = "Alice")),
        mailbox = null,
    )

    // -- verification words: both sides derive the same 4 words from the two device pks,
    // independent of argument order -- before any grant frame is ever exchanged ------------

    @Test
    fun verificationWordsMatchOnBothSidesRegardlessOfArgumentOrder() {
        val newDevicePk = fakePublicKey(fixedKey(0x10))
        val grantingDevicePk = fakePublicKey(fixedKey(0x20))

        // The new device (task 6.1) computes from (its own pk, the transport-level remote
        // id of the inbound connection); the granting device (task 6.2) computes from (its
        // own pk, the azl payload's embedded device_pk) -- i.e. the same two keys, handed
        // in the opposite order on each side.
        val wordsFromNewDeviceSide = VerificationWords.compute(newDevicePk, grantingDevicePk)
        val wordsFromGrantingDeviceSide = VerificationWords.compute(grantingDevicePk, newDevicePk)

        assertEquals(wordsFromNewDeviceSide, wordsFromGrantingDeviceSide)
        assertEquals(4, wordsFromNewDeviceSide.size)
    }

    // -- the new-device session sends LinkHello with the roles byte round-tripping, and a
    // mailbox request is visible to the granting side's decide() callback -------------------

    @Test
    fun rolesByteRoundTripsAndMailboxRequestIsVisibleToTheGrantingSide() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val newDevicePk = fakePublicKey(fixedKey(0x30)).toHex()

        val grantingJob = async {
            DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { devicePkHex, name, roles ->
                assertEquals(newDevicePk, devicePkHex)
                assertEquals("phone", name)
                assertEquals(ROLE_MAILBOX, roles, "the mailbox request bit must be visible to the granting side")
                DeviceLinkProtocol.GrantDecision.Reject("just checking the hello, not granting")
            }
        }
        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, newDevicePk, "phone", ROLE_MAILBOX)

        assertEquals(DeviceLinkProtocol.GrantSessionResult.REJECTED, grantingJob.await())
        assertIs<DeviceLinkProtocol.LinkOutcome.Rejected>(outcome)
    }

    // -- cancel: the granting side's decide() rejects -> LinkReject travels back, and no
    // certificate/bundle ever exists on the new-device side --------------------------------

    @Test
    fun cancelSendsLinkRejectAndPersistsNothing() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val newDevicePk = fakePublicKey(fixedKey(0x31)).toHex()

        val grantingJob = async {
            DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { _, _, _ ->
                DeviceLinkProtocol.GrantDecision.Reject("user declined")
            }
        }
        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, newDevicePk, "phone", 0)

        assertEquals(DeviceLinkProtocol.GrantSessionResult.REJECTED, grantingJob.await())
        val rejected = assertIs<DeviceLinkProtocol.LinkOutcome.Rejected>(outcome)
        assertEquals("user declined", rejected.reason)
        // Rejected carries no cert/bundle at all -- there is nothing a caller could
        // mistakenly persist even if it tried.
    }

    @Test
    fun grantSendsTheCertAndBundleBackToTheNewDevice() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val newDevicePk = fakePublicKey(fixedKey(0x32)).toHex()
        val bundle = sampleBundle()

        val grantingJob = async {
            DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { _, _, _ ->
                DeviceLinkProtocol.GrantDecision.Grant("azd-granted", bundle)
            }
        }
        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, newDevicePk, "phone", 0)

        assertEquals(DeviceLinkProtocol.GrantSessionResult.GRANTED, grantingJob.await())
        val granted = assertIs<DeviceLinkProtocol.LinkOutcome.Granted>(outcome)
        assertEquals("azd-granted", granted.cert)
        assertEquals(bundle, granted.bundle)
    }

    // -- task 6.7: the dialer (root-holding side) writes one priming blank line right after
    // opening the QUIC stream (ConnectService.beginGrantDial), because the acceptor's
    // accept_bi() only fires once the dialer has written bytes -- so every frame read on
    // this ALPN must skip blank lines, exactly like Rust proto.rs::read_frame. These two
    // tests pin that down on both reading sides; the QUIC-semantics half (that the priming
    // write is what unblocks the acceptor at all) can only be shown over a real transport,
    // which azula-cli's `link_handshake_completes_over_a_real_quic_connection` covers.

    @Test
    fun newDeviceSessionSkipsThePrimingBlankLineBeforeTheReply() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val newDevicePk = fakePublicKey(fixedKey(0x33)).toHex()
        val bundle = sampleBundle()

        // Simulate the fixed dialer: its priming blank line lands in the new device's
        // inbox ahead of the granting session's actual reply.
        grantingDeviceEnd.sendLine("")
        val grantingJob = async {
            DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { _, _, _ ->
                DeviceLinkProtocol.GrantDecision.Grant("azd-granted", bundle)
            }
        }
        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, newDevicePk, "phone", 0)

        assertEquals(DeviceLinkProtocol.GrantSessionResult.GRANTED, grantingJob.await())
        val granted = assertIs<DeviceLinkProtocol.LinkOutcome.Granted>(outcome)
        assertEquals("azd-granted", granted.cert)
        assertEquals(bundle, granted.bundle)
    }

    @Test
    fun grantingSessionSkipsBlankLinesBeforeTheHello() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        val newDevicePk = fakePublicKey(fixedKey(0x34)).toHex()

        // Blank lines ahead of the hello must be skipped, not decoded as a malformed frame
        // (Rust read_frame skips blanks on *every* read, so the Kotlin reader must too) --
        // two of them, to prove the reader loops rather than skipping exactly one.
        newDeviceEnd.sendLine("")
        newDeviceEnd.sendLine("")
        val grantingJob = async {
            DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { devicePkHex, name, _ ->
                assertEquals(newDevicePk, devicePkHex)
                assertEquals("phone", name)
                DeviceLinkProtocol.GrantDecision.Reject("hello made it through the blanks")
            }
        }
        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, newDevicePk, "phone", 0)

        assertEquals(DeviceLinkProtocol.GrantSessionResult.REJECTED, grantingJob.await())
        assertIs<DeviceLinkProtocol.LinkOutcome.Rejected>(outcome)
    }

    @Test
    fun grantingSessionWithNoHelloSendsNothing() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        newDeviceEnd.close() // the dialer hung up without ever sending a frame

        val result = DeviceLinkProtocol.runGrantingSession(grantingDeviceEnd) { _, _, _ ->
            throw AssertionError("decide() must not be called -- there was no hello to decide on")
        }
        assertEquals(DeviceLinkProtocol.GrantSessionResult.NO_HELLO, result)
    }

    @Test
    fun newDeviceSessionOnClosedConnectionIsRejectedNotThrown() = runTest {
        val (newDeviceEnd, grantingDeviceEnd) = streamPair()
        grantingDeviceEnd.close() // closed before any reply at all

        val outcome = DeviceLinkProtocol.runNewDeviceSession(newDeviceEnd, "abcd", "phone", 0)
        val rejected = assertIs<DeviceLinkProtocol.LinkOutcome.Rejected>(outcome)
        assertTrue(rejected.reason.contains("closed"), rejected.reason)
    }

    // -- verifyGrantedCert: the reference-mirroring "don't persist whatever arrives" checks -

    private fun issuedCert(rootSecret: ByteArray, devicePk: ByteArray, expiresAt: Long = 0L) =
        DeviceCertCodec.issue(
            roles = 0,
            rootPk = fakePublicKey(rootSecret),
            devicePk = devicePk,
            issuedAt = 1_700_000_000L,
            expiresAt = expiresAt,
            name = "new-device",
            rootSecret = rootSecret,
            ed25519 = ed25519,
        )

    @Test
    fun verifyGrantedCertAcceptsAValidUnrevokedGrant() {
        val rootSecret = fixedKey(1)
        val devicePk = fakePublicKey(fixedKey(0x40))
        val cert = issuedCert(rootSecret, devicePk)
        val bundle = sampleBundle()

        val verified = DeviceLinkProtocol.verifyGrantedCert(DeviceCertCodec.encode(cert), devicePk, bundle, ed25519, 1_700_000_500L)
        assertNotNull(verified)
        assertEquals(devicePk.toHex(), verified.devicePk.toHex())
    }

    @Test
    fun verifyGrantedCertRejectsAMalformedCertificate() {
        val bundle = sampleBundle()
        val verified = DeviceLinkProtocol.verifyGrantedCert("not-a-cert-at-all", fakePublicKey(fixedKey(0x41)), bundle, ed25519, 1_700_000_500L)
        assertNull(verified)
    }

    @Test
    fun verifyGrantedCertRejectsACertNamingADifferentDeviceKey() {
        val rootSecret = fixedKey(2)
        val devicePk = fakePublicKey(fixedKey(0x42))
        val someoneElsesPk = fakePublicKey(fixedKey(0x43))
        val cert = issuedCert(rootSecret, devicePk)
        val bundle = sampleBundle()

        // The grant names `devicePk`, but we expect it to name `someoneElsesPk` -- e.g. a
        // buggy/malicious granting device replying with the wrong cert.
        val verified = DeviceLinkProtocol.verifyGrantedCert(DeviceCertCodec.encode(cert), someoneElsesPk, bundle, ed25519, 1_700_000_500L)
        assertNull(verified)
    }

    @Test
    fun verifyGrantedCertRejectsACertThatFailsSignatureVerification() {
        val rootSecret = fixedKey(3)
        val devicePk = fakePublicKey(fixedKey(0x44))
        val cert = issuedCert(rootSecret, devicePk)
        val tampered = cert.copy(signature = cert.signature.copyOf().also { it[0] = it[0] xor 0xff.toByte() })
        val bundle = sampleBundle()

        val verified = DeviceLinkProtocol.verifyGrantedCert(DeviceCertCodec.encode(tampered), devicePk, bundle, ed25519, 1_700_000_500L)
        assertNull(verified)
    }

    @Test
    fun verifyGrantedCertRejectsAnExpiredCertificate() {
        val rootSecret = fixedKey(4)
        val devicePk = fakePublicKey(fixedKey(0x45))
        val cert = issuedCert(rootSecret, devicePk, expiresAt = 1_700_000_001L)
        val bundle = sampleBundle()

        val verified = DeviceLinkProtocol.verifyGrantedCert(DeviceCertCodec.encode(cert), devicePk, bundle, ed25519, 1_700_000_500L)
        assertNull(verified)
    }

    @Test
    fun verifyGrantedCertRejectsACertAlreadyRevokedInTheBundle() {
        val rootSecret = fixedKey(5)
        val devicePk = fakePublicKey(fixedKey(0x46))
        val cert = issuedCert(rootSecret, devicePk)

        val revocation = DeviceRevocationCodec.issue(
            rootPk = fakePublicKey(rootSecret),
            revokedDevicePk = devicePk,
            revokedAt = 1_700_000_100L,
            rootSecret = rootSecret,
            ed25519 = ed25519,
        )
        val bundle = sampleBundle().copy(revocations = listOf(DeviceRevocationCodec.encode(revocation)))

        val verified = DeviceLinkProtocol.verifyGrantedCert(DeviceCertCodec.encode(cert), devicePk, bundle, ed25519, 1_700_000_500L)
        assertNull(verified)
    }

    // -- buildIdentityBundle: certs/revocations/contacts assembled from raw log entries -----
    // (AccountSyncFold's fold only cares about lamport/ts/device_pk order, not prev_hash
    // continuity, so every entry below reuses ZERO_PREV_HASH -- there's no chain to build.)

    @Test
    fun buildIdentityBundleAssemblesCertsRevocationsAndContactsFromRawEntries() {
        val rootSecret = fixedKey(6)
        val rootPk = fakePublicKey(rootSecret)
        val existingDeviceSecret = fixedKey(0x50)
        val existingDevicePk = fakePublicKey(existingDeviceSecret)
        val newDevicePk = fakePublicKey(fixedKey(0x60))

        val existingCert = DeviceCertCodec.issue(0, rootPk, existingDevicePk, 1_700_000_000L, 0L, "laptop", rootSecret, ed25519)
        val newCert = DeviceCertCodec.issue(0, rootPk, newDevicePk, 1_700_000_200L, 0L, "phone", rootSecret, ed25519)

        val deviceAddBody = encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(existingCert)))
        val contactAddBody = encodeContactKeyBody(ContactKeyBody(rootPk = "contact-root-pk", name = "Alice"))
        val contactRemoveBody = encodeContactKeyBody(ContactKeyBody(endpointId = "legacy-endpoint-id"))

        val entries = listOf(
            LogEntryCodec.sign(EventKind.DEVICE_ADD.wireValue, existingDevicePk, 1L, 1L, 1000L, ZERO_PREV_HASH, deviceAddBody, existingDeviceSecret, ed25519),
            LogEntryCodec.sign(EventKind.CONTACT_ADD.wireValue, existingDevicePk, 2L, 2L, 2000L, ZERO_PREV_HASH, contactAddBody, existingDeviceSecret, ed25519),
            // A second contact added then removed -- must NOT appear in the snapshot.
            LogEntryCodec.sign(EventKind.CONTACT_ADD.wireValue, existingDevicePk, 3L, 3L, 3000L, ZERO_PREV_HASH, contactRemoveBody, existingDeviceSecret, ed25519),
            LogEntryCodec.sign(EventKind.CONTACT_REMOVE.wireValue, existingDevicePk, 4L, 4L, 4000L, ZERO_PREV_HASH, contactRemoveBody, existingDeviceSecret, ed25519),
        )

        val bundle = DeviceLinkProtocol.buildIdentityBundle(rootPk, rootPk.toHex(), entries, newCert, ed25519, 1_700_000_500L)

        assertEquals(rootPk.toHex(), bundle.rootPk)
        assertTrue(bundle.certs.contains(DeviceCertCodec.encode(existingCert)), "existing device's cert must be included")
        assertTrue(bundle.certs.contains(DeviceCertCodec.encode(newCert)), "the newly-issued cert must be included")
        assertEquals(1, bundle.contacts.size, "the removed contact must not appear in the snapshot")
        assertEquals("contact-root-pk", bundle.contacts.single().rootPk)
        assertEquals("Alice", bundle.contacts.single().name)
        assertNull(bundle.mailbox, "no mailbox-hint store exists yet -- see this function's kdoc")
    }

    @Test
    fun buildIdentityBundleExcludesARevokedDevicesCertButListsTheRevocation() {
        val rootSecret = fixedKey(7)
        val rootPk = fakePublicKey(rootSecret)
        val revokedDeviceSecret = fixedKey(0x70)
        val revokedDevicePk = fakePublicKey(revokedDeviceSecret)
        val newDevicePk = fakePublicKey(fixedKey(0x71))

        val revokedCert = DeviceCertCodec.issue(0, rootPk, revokedDevicePk, 1_700_000_000L, 0L, "old-phone", rootSecret, ed25519)
        val revocation = DeviceRevocationCodec.issue(rootPk, revokedDevicePk, 1_700_000_100L, rootSecret, ed25519)
        val newCert = DeviceCertCodec.issue(0, rootPk, newDevicePk, 1_700_000_200L, 0L, "phone", rootSecret, ed25519)

        val deviceAddBody = encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(revokedCert)))
        val deviceRevokeBody = encodeDeviceRevokeBody(DeviceRevokeBody(revocation = DeviceRevocationCodec.encode(revocation)))

        val entries = listOf(
            LogEntryCodec.sign(EventKind.DEVICE_ADD.wireValue, revokedDevicePk, 1L, 1L, 1000L, ZERO_PREV_HASH, deviceAddBody, revokedDeviceSecret, ed25519),
            LogEntryCodec.sign(EventKind.DEVICE_REVOKE.wireValue, revokedDevicePk, 2L, 2L, 2000L, ZERO_PREV_HASH, deviceRevokeBody, revokedDeviceSecret, ed25519),
        )

        val bundle = DeviceLinkProtocol.buildIdentityBundle(rootPk, rootPk.toHex(), entries, newCert, ed25519, 1_700_000_500L)

        assertTrue(bundle.certs.none { it == DeviceCertCodec.encode(revokedCert) }, "a revoked device's cert must be excluded")
        assertTrue(bundle.revocations.contains(DeviceRevocationCodec.encode(revocation)), "the revocation itself must still be listed")
    }
}
