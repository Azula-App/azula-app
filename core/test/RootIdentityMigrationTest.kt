import dev.azula.core.DeviceCertCodec
import dev.azula.core.RootIdentityMigration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * multi-device-identity task 3.2: on first launch with an existing endpoint key and no root
 * entry, the endpoint secret becomes the root secret unchanged and a self-certificate with
 * `device_pk == root_pk` is issued (design.md Migration Plan step 2). This exercises the
 * pure self-certification computation in isolation -- see [RootIdentityMigration]'s kdoc
 * for why the storage decision ("is there already a root entry?") and persistence live in
 * `network-real` instead.
 */
class RootIdentityMigrationTest {
    private val ed25519 = FakeEd25519()

    // The migrating device's former endpoint secret -- becomes the root secret unchanged.
    private val endpointSecret = fixedKey(9)
    private val devicePk = fakePublicKey(endpointSecret)
    private val issuedAt = 1_700_000_000L

    @Test
    fun selfCertHasMatchingRootAndDevicePk() {
        val cert = RootIdentityMigration.selfCertify(endpointSecret, devicePk, issuedAt, ed25519)

        assertTrue(cert.rootPk.contentEquals(cert.devicePk), "device_pk must equal root_pk for a self-certified migration")
        assertTrue(cert.rootPk.contentEquals(devicePk))
    }

    @Test
    fun selfCertVerifiesAgainstItsOwnEmbeddedRootPk() {
        val cert = RootIdentityMigration.selfCertify(endpointSecret, devicePk, issuedAt, ed25519)

        assertTrue(DeviceCertCodec.verify(cert, ed25519, nowEpochSeconds = issuedAt))
    }

    @Test
    fun selfCertHasNoRolesAndNeverExpires() {
        val cert = RootIdentityMigration.selfCertify(endpointSecret, devicePk, issuedAt, ed25519)

        assertEquals(0, cert.roles, "migration doesn't request the mailbox/bot role bits")
        assertEquals(0L, cert.expiresAt, "a self-cert from migration never expires")
        assertEquals(issuedAt, cert.issuedAt)
        assertEquals("", cert.name)
    }

    @Test
    fun selfCertificationIsDeterministic() {
        // Same inputs must produce a byte-identical cert -- the whole point being that this
        // is safe to recompute on every launch until an event-log store exists to persist
        // it once (multi-device-identity task 3.4).
        val first = RootIdentityMigration.selfCertify(endpointSecret, devicePk, issuedAt, ed25519)
        val second = RootIdentityMigration.selfCertify(endpointSecret, devicePk, issuedAt, ed25519)

        assertEquals(DeviceCertCodec.encode(first), DeviceCertCodec.encode(second))
    }

    @Test
    fun rejectsAWrongSizeRootSecret() {
        assertFailsWith<IllegalArgumentException> {
            RootIdentityMigration.selfCertify(ByteArray(31), devicePk, issuedAt, ed25519)
        }
    }

    @Test
    fun rejectsAWrongSizeDevicePk() {
        assertFailsWith<IllegalArgumentException> {
            RootIdentityMigration.selfCertify(endpointSecret, ByteArray(31), issuedAt, ed25519)
        }
    }
}
