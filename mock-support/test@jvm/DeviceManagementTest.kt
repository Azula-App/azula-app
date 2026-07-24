import dev.azula.core.DeviceCertCodec
import dev.azula.core.Ed25519
import dev.azula.core.EventKind
import dev.azula.core.Sha256
import dev.azula.mock.FakeTransport
import dev.azula.net.IrohTransport
import dev.azula.sync.DeviceAddBody
import dev.azula.sync.FileEventLogStore
import dev.azula.sync.allEntries
import dev.azula.sync.encodeDeviceAddBody
import dev.azula.sync.toHexLower
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * multi-device-identity task 6.3 (device management screen), driven end to end through
 * [testAzulaState]'s [dev.azula.state.AzulaState] surface, against a real
 * [FileEventLogStore] -- same posture as `AccountSyncWiringTest.kt`. Covers the three
 * scenarios this task's "Tests" note calls out: renaming is local-only and never mints a
 * certificate, a non-root-holding device cannot revoke, and revoking appends `device_revoke`
 * and the device disappears from the folded device set.
 */
class DeviceManagementTest {
    private val tempDir = createTempDirectory("device-management-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private class ManagementEd25519 : Ed25519 {
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

    private val ed25519 = ManagementEd25519()
    private fun fakePublicKey(secret: ByteArray): ByteArray = Sha256.hash(secret)
    private fun fixedKey(fill: Int): ByteArray = ByteArray(32) { ((fill + it) and 0xFF).toByte() }

    private val secret = fixedKey(0x05)
    private val devicePk = fakePublicKey(secret)
    private val devicePkHex = devicePk.toHexLower()

    private fun newStore() = FileEventLogStore(File(tempDir, "log-${System.nanoTime()}"), ed25519) { devicePkHex }

    /** A fixed, [ed25519]-consistent identity transport (mirrors `AccountSyncWiringTest`'s
     *  `IdentityFakeTransport`), with [holdsRoot] toggling whether it holds the root secret
     *  at all -- `false` models a QR-linked device for the non-root-holding revoke test. */
    private class ManagementTestTransport(
        private val secret: ByteArray,
        private val fixedNodeIdHex: String,
        private val holdsRoot: Boolean,
        private val delegate: FakeTransport = FakeTransport(),
    ) : IrohTransport by delegate {
        override val nodeId: String? get() = fixedNodeIdHex
        override fun rootSecretBytes(): ByteArray? = if (holdsRoot) secret else null
        override fun secretKeyBytes(): ByteArray = secret
        override fun publicKeyFor(secret: ByteArray): ByteArray = Sha256.hash(secret)
    }

    @Test
    fun renamingIsLocalAndDoesNotMintANewCertificate() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(
            backgroundScope, ManagementTestTransport(secret, devicePkHex, holdsRoot = true),
            eventLogStore = store, ed25519 = ed25519,
        )
        azulaState.start()
        runCurrent()

        val deviceAddCountBefore = store.allEntries().count { it.knownKind == EventKind.DEVICE_ADD }
        azulaState.refreshDevices()
        val before = azulaState.devices.single { it.devicePkHex == devicePkHex }
        assertEquals(null, before.localName, "no local override yet")

        azulaState.renameDeviceLocally(devicePkHex, "my phone")
        azulaState.refreshDevices()

        val after = azulaState.devices.single { it.devicePkHex == devicePkHex }
        assertEquals("my phone", after.localName)
        assertEquals(before.certName, after.certName, "the certificate's own name field must be untouched by a local rename")

        val deviceAddCountAfter = store.allEntries().count { it.knownKind == EventKind.DEVICE_ADD }
        assertEquals(deviceAddCountBefore, deviceAddCountAfter, "renaming must never append a new device_add / mint a new certificate")
    }

    @Test
    fun nonRootHoldingDeviceCannotRevoke() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(
            backgroundScope, ManagementTestTransport(secret, devicePkHex, holdsRoot = false),
            eventLogStore = store, ed25519 = ed25519,
        )
        azulaState.start()
        runCurrent()

        assertFalse(azulaState.canRevokeDevices(), "a QR-linked (non-root-holding) device must not be offered a revoke control")

        val entriesBefore = store.allEntries().size
        val revoked = azulaState.revokeDevice(devicePkHex)
        runCurrent()

        assertFalse(revoked, "a non-root-holding device's revoke attempt must fail")
        assertEquals(entriesBefore, store.allEntries().size, "a failed/refused revoke attempt must append nothing")
    }

    @Test
    fun revokingAppendsDeviceRevokeAndDeviceDisappearsFromTheFoldedSet() = runTest {
        val store = newStore()
        val azulaState = testAzulaState(
            backgroundScope, ManagementTestTransport(secret, devicePkHex, holdsRoot = true),
            eventLogStore = store, ed25519 = ed25519,
        )
        azulaState.start()
        runCurrent()

        // Mint a second device directly onto this identity's log -- the exact shape
        // AccountSyncService.issueDeviceLinkGrant itself produces (task 6.2), just without a
        // live link-flow dial (already covered by that task's own tests).
        val otherSecret = fixedKey(0x99)
        val otherDevicePk = fakePublicKey(otherSecret)
        val otherDevicePkHex = otherDevicePk.toHexLower()
        val otherCert = DeviceCertCodec.issue(0, devicePk, otherDevicePk, 1_700_000_000L, 0L, "other-device", secret, ed25519)
        store.appendOwn(
            devicePk, secret, EventKind.DEVICE_ADD.wireValue, 1_700_000_000_000L,
            encodeDeviceAddBody(DeviceAddBody(cert = DeviceCertCodec.encode(otherCert))),
        )

        azulaState.refreshDevices()
        assertTrue(azulaState.devices.any { it.devicePkHex == otherDevicePkHex }, "sanity: the other device is in the folded set before revoking")
        assertTrue(azulaState.canRevokeDevices(), "this device holds the root secret and must be able to revoke")

        val ok = azulaState.revokeDevice(otherDevicePkHex)
        runCurrent()
        assertTrue(ok)

        val revokeEntries = store.allEntries().filter { it.knownKind == EventKind.DEVICE_REVOKE }
        assertEquals(1, revokeEntries.size, "revoking must append exactly one device_revoke entry")

        azulaState.refreshDevices()
        assertFalse(azulaState.devices.any { it.devicePkHex == otherDevicePkHex }, "the revoked device must disappear from the folded device set")
    }
}
