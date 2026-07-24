package dev.azula.sync

import dev.azula.core.DeviceCertCodec
import dev.azula.core.DeviceRevocationCodec
import dev.azula.core.EventKind
import dev.azula.core.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AccountSyncFold] against
 * azula-docs/openspec/changes/multi-device-identity/specs/account-sync/spec.md's
 * "Derived State Is a Deterministic Fold". [FakeEd25519] is a deterministic test-only
 * stand-in (see its kdoc) -- fixed keys throughout so every scenario is reproducible.
 *
 * The fold itself never inspects a [LogEntry]'s own signature (that's `EventLogStore`'s
 * job at insert/append time -- see `FileEventLogStoreTest` in `sync-real`), so most
 * entries here are built directly via the [entry] helper with a zeroed signature; only
 * `device_add`/`device_revoke` bodies embed a *separately* root-signed
 * `DeviceCert`/`DeviceRevocation` that [AccountSyncFold.fold] does verify.
 */
class AccountSyncFoldTest {
    private val ed25519 = FakeEd25519()
    private val rootSecret = fixedKey(0x01)
    private val rootPk = fakePublicKey(rootSecret)
    private val foreignRootSecret = fixedKey(0xC0)
    private val foreignRootPk = fakePublicKey(foreignRootSecret)

    private val deviceA = fixedKey(0x40)
    private val deviceAPk = fakePublicKey(deviceA)
    private val deviceB = fixedKey(0x60)
    private val deviceBPk = fakePublicKey(deviceB)

    private val zeroHash = ByteArray(32)
    private val zeroSig = ByteArray(64)

    private fun entry(kind: Int, author: ByteArray, seq: Long, lamport: Long, tsMs: Long, body: ByteArray): LogEntry =
        LogEntry(
            version = 1, kind = kind, authorDevicePk = author, seq = seq, lamport = lamport,
            tsMs = tsMs, prevHash = zeroHash, body = body, signature = zeroSig,
        )

    private fun messageOut(author: ByteArray, seq: Long, lamport: Long, conversation: String, text: String, id: String? = null) =
        entry(
            EventKind.MESSAGE_OUT.wireValue, author, seq, lamport, lamport * 1000,
            encodeMessageOutBody(MessageOutBody(conversation, text, id)),
        )

    private fun messageIn(author: ByteArray, seq: Long, lamport: Long, conversation: String, fromDevicePk: String, text: String, id: String? = null) =
        entry(
            EventKind.MESSAGE_IN.wireValue, author, seq, lamport, lamport * 1000,
            encodeMessageInBody(MessageInBody(conversation, fromDevicePk, text, id)),
        )

    private fun readMarker(author: ByteArray, seq: Long, lamport: Long, conversation: String, upToLamport: Long) =
        entry(
            EventKind.READ_MARKER.wireValue, author, seq, lamport, lamport * 1000,
            encodeReadMarkerBody(ReadMarkerBody(conversation, upToLamport)),
        )

    private fun contactAdd(author: ByteArray, seq: Long, lamport: Long, key: String, name: String? = null) =
        entry(
            EventKind.CONTACT_ADD.wireValue, author, seq, lamport, lamport * 1000,
            encodeContactKeyBody(ContactKeyBody(rootPk = key, name = name)),
        )

    private fun contactRemove(author: ByteArray, seq: Long, lamport: Long, key: String) =
        entry(
            EventKind.CONTACT_REMOVE.wireValue, author, seq, lamport, lamport * 1000,
            encodeContactKeyBody(ContactKeyBody(rootPk = key)),
        )

    private fun profileUpdate(author: ByteArray, seq: Long, lamport: Long, name: String? = null, description: String? = null) =
        entry(
            EventKind.PROFILE_UPDATE.wireValue, author, seq, lamport, lamport * 1000,
            encodeProfileUpdateBody(ProfileUpdateBody(name, description)),
        )

    private fun deviceAddEntry(author: ByteArray, seq: Long, lamport: Long, certRoot: ByteArray, certDevicePk: ByteArray, name: String = "d") =
        entry(
            EventKind.DEVICE_ADD.wireValue, author, seq, lamport, lamport * 1000,
            encodeDeviceAddBody(
                DeviceAddBody(
                    DeviceCertCodec.encode(
                        DeviceCertCodec.issue(0, certRoot, certDevicePk, 1_700_000_000L, 0L, name, if (certRoot.contentEquals(rootPk)) rootSecret else foreignRootSecret, ed25519),
                    ),
                ),
            ),
        )

    private fun deviceRevokeEntry(author: ByteArray, seq: Long, lamport: Long, revokedDevicePk: ByteArray) =
        entry(
            EventKind.DEVICE_REVOKE.wireValue, author, seq, lamport, lamport * 1000,
            encodeDeviceRevokeBody(
                DeviceRevokeBody(
                    DeviceRevocationCodec.encode(
                        DeviceRevocationCodec.issue(rootPk, revokedDevicePk, 1_700_100_000L, rootSecret, ed25519),
                    ),
                ),
            ),
        )

    private fun fold(entries: List<LogEntry>) = AccountSyncFold.fold(entries, rootPk, ed25519, nowEpochSeconds = 1_700_000_500L)

    // -- Contact add/remove resolution by order --------------------------------------------

    @Test
    fun contactResolvesToTheHighestOrderedEntry() {
        val entries = listOf(
            contactAdd(deviceAPk, 1, lamport = 1, key = "cafe", name = "Alice"),
            contactRemove(deviceAPk, 2, lamport = 3, key = "cafe"),
            contactAdd(deviceAPk, 3, lamport = 5, key = "cafe", name = "Alice2"),
        )
        val state = fold(entries)
        assertEquals(mapOf("cafe" to ContactInfo("cafe", "Alice2")), state.contacts)
    }

    @Test
    fun contactRemovedByTheHighestOrderedEntryIsAbsent() {
        val entries = listOf(
            contactAdd(deviceAPk, 1, lamport = 1, key = "cafe", name = "Alice"),
            contactRemove(deviceAPk, 2, lamport = 9, key = "cafe"), // remove is the highest-ordered
        )
        assertTrue(fold(entries).contacts.isEmpty())
    }

    @Test
    fun independentContactsDoNotInterfere() {
        val entries = listOf(
            contactAdd(deviceAPk, 1, lamport = 1, key = "aaaa", name = "Alice"),
            contactAdd(deviceAPk, 2, lamport = 2, key = "bbbb", name = "Bob"),
            contactRemove(deviceAPk, 3, lamport = 3, key = "aaaa"),
        )
        assertEquals(mapOf("bbbb" to ContactInfo("bbbb", "Bob")), fold(entries).contacts)
    }

    @Test
    fun inputOrderDoesNotAffectContactResolution() {
        // Same three entries as contactResolvesToTheHighestOrderedEntry, fed reversed.
        val entries = listOf(
            contactAdd(deviceAPk, 3, lamport = 5, key = "cafe", name = "Alice2"),
            contactRemove(deviceAPk, 2, lamport = 3, key = "cafe"),
            contactAdd(deviceAPk, 1, lamport = 1, key = "cafe", name = "Alice"),
        )
        assertEquals(mapOf("cafe" to ContactInfo("cafe", "Alice2")), fold(entries).contacts)
    }

    // -- Profile last-writer-wins, per field ------------------------------------------------

    @Test
    fun profileFieldsAreIndependentlyLastWriterWins() {
        val entries = listOf(
            profileUpdate(deviceAPk, 1, lamport = 1, name = "Alice"),
            profileUpdate(deviceAPk, 2, lamport = 2, description = "Loves tea"), // name absent here
        )
        // The second entry doesn't mention `name`, so it must not clear the first's.
        assertEquals(ProfileInfo("Alice", "Loves tea"), fold(entries).profile)
    }

    @Test
    fun laterProfileUpdateOverwritesTheSameField() {
        val entries = listOf(
            profileUpdate(deviceAPk, 1, lamport = 1, name = "Alice"),
            profileUpdate(deviceAPk, 2, lamport = 5, name = "Alicia"),
        )
        assertEquals(ProfileInfo("Alicia", null), fold(entries).profile)
    }

    // -- Device set minus revocations --------------------------------------------------------

    @Test
    fun deviceSetIsTheUnionOfValidCerts() {
        val entries = listOf(
            deviceAddEntry(deviceAPk, 1, lamport = 1, certRoot = rootPk, certDevicePk = deviceAPk),
            deviceAddEntry(deviceAPk, 2, lamport = 2, certRoot = rootPk, certDevicePk = deviceBPk),
        )
        val devices = fold(entries).devices
        assertEquals(setOf(deviceAPk.toHexLower(), deviceBPk.toHexLower()), devices.map { it.devicePk.toHexLower() }.toSet())
    }

    @Test
    fun revokedDeviceIsExcludedFromTheDeviceSet() {
        val entries = listOf(
            deviceAddEntry(deviceAPk, 1, lamport = 1, certRoot = rootPk, certDevicePk = deviceAPk),
            deviceAddEntry(deviceAPk, 2, lamport = 2, certRoot = rootPk, certDevicePk = deviceBPk),
            deviceRevokeEntry(deviceAPk, 3, lamport = 3, revokedDevicePk = deviceBPk),
        )
        val devices = fold(entries).devices
        assertEquals(listOf(deviceAPk.toHexLower()), devices.map { it.devicePk.toHexLower() })
    }

    @Test
    fun certFromAForeignRootIsExcludedEvenWithoutRevocation() {
        val entries = listOf(
            deviceAddEntry(deviceAPk, 1, lamport = 1, certRoot = rootPk, certDevicePk = deviceAPk),
            deviceAddEntry(deviceAPk, 2, lamport = 2, certRoot = foreignRootPk, certDevicePk = deviceBPk),
        )
        val devices = fold(entries).devices
        assertEquals(listOf(deviceAPk.toHexLower()), devices.map { it.devicePk.toHexLower() })
    }

    @Test
    fun revocationOrderDoesNotMatterForFinalDeviceSet() {
        // Revocation entry fed in before the add, but its lamport is still highest.
        val entries = listOf(
            deviceRevokeEntry(deviceAPk, 1, lamport = 5, revokedDevicePk = deviceBPk),
            deviceAddEntry(deviceAPk, 2, lamport = 1, certRoot = rootPk, certDevicePk = deviceAPk),
            deviceAddEntry(deviceAPk, 3, lamport = 2, certRoot = rootPk, certDevicePk = deviceBPk),
        )
        val devices = fold(entries).devices
        assertEquals(listOf(deviceAPk.toHexLower()), devices.map { it.devicePk.toHexLower() })
    }

    // -- (conversation, id) dedup for message_in ---------------------------------------------

    @Test
    fun messageInDedupesByConversationAndId() {
        val entries = listOf(
            messageIn(deviceAPk, 1, lamport = 1, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi", id = "retry-1"),
            // A retry delivered to (and logged by) a second device, same id: must fold once.
            messageIn(deviceBPk, 1, lamport = 2, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi", id = "retry-1"),
        )
        val messages = fold(entries).conversations.getValue("cafe")
        assertEquals(1, messages.size)
    }

    @Test
    fun messageInWithoutIdIsNeverDeduped() {
        val entries = listOf(
            messageIn(deviceAPk, 1, lamport = 1, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi"),
            messageIn(deviceBPk, 1, lamport = 2, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi"),
        )
        val messages = fold(entries).conversations.getValue("cafe")
        assertEquals(2, messages.size)
    }

    @Test
    fun differentIdsInTheSameConversationAreBothKept() {
        val entries = listOf(
            messageIn(deviceAPk, 1, lamport = 1, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi", id = "a"),
            messageIn(deviceAPk, 2, lamport = 2, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "there", id = "b"),
        )
        assertEquals(2, fold(entries).conversations.getValue("cafe").size)
    }

    // -- Unknown kinds: excluded from the fold -----------------------------------------------

    @Test
    fun unknownKindIsExcludedFromDerivedState() {
        val known = messageOut(deviceAPk, 1, lamport = 1, conversation = "cafe", text = "hi")
        val unknown = entry(0xEE, deviceAPk, 2, lamport = 2, tsMs = 2000, body = "{\"anything\":true}".encodeToByteArray())
        val state = fold(listOf(known, unknown))
        assertEquals(1, state.conversations.getValue("cafe").size)
        assertTrue(state.contacts.isEmpty())
        assertEquals(ProfileInfo(), state.profile)
        assertTrue(state.devices.isEmpty())
    }

    @Test
    fun malformedBodyForAKnownKindIsSkippedRatherThanCrashing() {
        val bad = entry(EventKind.MESSAGE_OUT.wireValue, deviceAPk, 1, lamport = 1, tsMs = 1000, body = "not json".encodeToByteArray())
        val state = fold(listOf(bad))
        assertTrue(state.conversations.isEmpty())
    }

    // -- Read markers -------------------------------------------------------------------------

    @Test
    fun readMarkerTracksTheHighestOrderedEntryPerConversation() {
        val entries = listOf(
            readMarker(deviceAPk, 1, lamport = 1, conversation = "cafe", upToLamport = 1),
            readMarker(deviceAPk, 2, lamport = 5, conversation = "cafe", upToLamport = 4),
        )
        assertEquals(mapOf("cafe" to 4L), fold(entries).readMarkers)
    }

    // -- Determinism: the central property this task exists to prove ------------------------

    @Test
    fun sameEntriesInDifferentInsertionOrdersProduceIdenticalDerivedState() {
        val entries = listOf(
            messageOut(deviceAPk, 1, lamport = 1, conversation = "cafe", text = "hello"),
            messageIn(deviceBPk, 1, lamport = 2, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi back", id = "m1"),
            contactAdd(deviceAPk, 2, lamport = 3, key = "cafe", name = "Contact One"),
            profileUpdate(deviceAPk, 3, lamport = 4, name = "Me", description = "Bio"),
            deviceAddEntry(deviceAPk, 4, lamport = 5, certRoot = rootPk, certDevicePk = deviceAPk),
            deviceAddEntry(deviceAPk, 5, lamport = 6, certRoot = rootPk, certDevicePk = deviceBPk),
            deviceRevokeEntry(deviceAPk, 6, lamport = 7, revokedDevicePk = deviceBPk),
            readMarker(deviceAPk, 7, lamport = 8, conversation = "cafe", upToLamport = 2),
            entry(0xEE, deviceAPk, 8, lamport = 9, tsMs = 9000, body = "{}".encodeToByteArray()), // unknown kind
            // A second delivery of the very same message_in id, from a different device, to
            // exercise dedup interacting with reordering too.
            messageIn(deviceAPk, 9, lamport = 10, conversation = "cafe", fromDevicePk = deviceBPk.toHexLower(), text = "hi back", id = "m1"),
        )

        val forward = fold(entries)
        val reversed = fold(entries.reversed())
        val shuffled = fold(listOf(entries[3], entries[7], entries[0], entries[9], entries[1], entries[8], entries[5], entries[2], entries[6], entries[4]))

        assertEquals(forward, reversed)
        assertEquals(forward, shuffled)

        // Sanity on the actual content, so this test would fail loudly if the fold were wrong
        // in a way that still happened to be order-independent.
        assertEquals(1, forward.conversations.getValue("cafe").count { !it.outgoing }) // deduped
        assertEquals(mapOf("cafe" to ContactInfo("cafe", "Contact One")), forward.contacts)
        assertEquals(ProfileInfo("Me", "Bio"), forward.profile)
        assertEquals(listOf(deviceAPk.toHexLower()), forward.devices.map { it.devicePk.toHexLower() })
        assertEquals(mapOf("cafe" to 2L), forward.readMarkers)
    }
}
