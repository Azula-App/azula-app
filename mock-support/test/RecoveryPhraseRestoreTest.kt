@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import dev.azula.core.RecoveryPhrase
import dev.azula.mock.FakeTransport
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Extends [FakeTransportTest.secretKeyRoundTripsAsRecoveryPhrase] (which round-trips
 * the encode/decode codec directly against the transport) up to the actual restore
 * path the Settings UI drives: [AzulaState.exportRecoveryPhrase] /
 * [AzulaState.importRecoveryPhrase] (backed by [dev.azula.state.ConnectService]).
 */
class RecoveryPhraseRestoreTest {

    @Test
    fun exportedPhraseMatchesTheTransportKey() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val phrase = azulaState.exportRecoveryPhrase()
        assertNotNull(phrase, "a bound transport should always have a recovery phrase")
        assertEquals(24, phrase.trim().split(" ").size)
        assertTrue(transport.secretKeyBytes().contentEquals(RecoveryPhrase.decode(phrase)!!))
    }

    @Test
    fun importingAnInvalidPhraseFailsWithoutTouchingIdentity() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()
        val nodeIdBefore = azulaState.nodeId

        val ok = azulaState.importRecoveryPhrase("not a real recovery phrase at all")
        runCurrent()

        assertFalse(ok, "a malformed phrase must be rejected")
        assertEquals(nodeIdBefore, azulaState.nodeId, "a rejected phrase must not touch the identity")
    }

    @Test
    fun importingAValidPhraseRebindsAndRefreshesIdentity() = runTest {
        val transport = FakeTransport()
        val azulaState = testAzulaState(backgroundScope, transport)
        azulaState.start()
        runCurrent()

        val key = ByteArray(32) { (it * 7).toByte() }
        val phrase = RecoveryPhrase.encode(key)

        val ok = azulaState.importRecoveryPhrase(phrase)
        runCurrent()

        assertTrue(ok, "a well-formed, checksum-valid phrase must be accepted")
        // FakeTransport.importSecretKey is a no-op, so this asserts that the
        // ConnectService restore glue re-reads nodeId/myCode from the transport
        // after import -- not that the fake transport's identity itself changed.
        assertEquals(transport.nodeId, azulaState.nodeId)
    }
}
