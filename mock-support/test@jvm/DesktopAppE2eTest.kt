@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import dev.azula.mock.FakeTransport
import dev.azula.mock.InMemoryBlobStore
import dev.azula.model.ConversationKind
import dev.azula.net.Frame
import dev.azula.state.AzulaState
import dev.azula.ui.DesktopApp
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop (JVM) Compose UI end-to-end tests: mounts the real [DesktopApp] root
 * composable -- the same one `jvm-app-mock/src/main.kt`'s `AzulaApp()` picks for
 * any window wide enough to count as desktop (see `shared/src/dev/azula/App.kt`)
 * -- over a [FakeTransport]-backed [AzulaState], driving it with real clicks and
 * typing via [runComposeUiTest] wherever the UI surface allows it. A couple of
 * seams (media send, an inbound offer) are injected directly at the picker-
 * callback/transport boundary instead, since there's no way to drive a native
 * AWT file dialog or a remote peer from here -- see each test's comment.
 *
 * Each test builds its own [AzulaState] via [testAzulaState] (mock-support/test/TestSupport.kt)
 * on a fresh scope -- never the process-wide `AppStateHost.shared` -- so tests
 * can't see each other's conversations. This is the same DI wiring
 * `dev.azula.mock.buildMockState` uses (null message/profile/peer stores, a real
 * [InMemoryBlobStore]) but, unlike `buildMockState`, lets a test hold onto the
 * exact [FakeTransport] instance -- required to seed [FakeTransport.blobs] and
 * call [FakeTransport.injectIncoming] for the inbound-offer test.
 *
 * [dev.azula.state.MediaService] and [dev.azula.state.ConnectService] both do
 * their real work on a real background dispatcher (`Dispatchers.Default`), not
 * anything tied to Compose's test clock -- the same "never advanceUntilIdle"
 * situation flagged in MediaServiceTest.kt -- so every assertion below that
 * depends on that work lands via a real (non-virtual) `waitUntil*` against the
 * rendered UI, polling until it either shows up or the timeout trips.
 */
class DesktopAppE2eTest {

    /** Drives the sidebar's connect flow exactly like `e2e/android.yaml`'s peer-connect
     *  section: open the connect pane, paste the mock ticket, hit connect, and wait for
     *  the loopback's greeting to land -- then resolves the PEER conversation it created. */
    private fun ComposeUiTest.connectToMockPeer(state: AzulaState): String {
        // "connect a peer" also appears as the (unclickable) ConnectPane header
        // title (DesktopApp.kt's ConnectPane) -- disambiguate against the
        // sidebar's actual ConnectRow via its click action.
        onNode(hasText("connect a peer") and hasClickAction()).performClick()
        onNodeWithTag("connect-input").performTextInput("azula-mock-peer")
        onNodeWithText("connect").performClick()
        waitUntilAtLeastOneExists(hasText("linked over iroh", substring = true), timeoutMillis = 8_000)
        return state.conversations.single { it.kind == ConversationKind.PEER }.id
    }

    @Test
    fun homeRendersBrandAndTheAutoSurfacedTerminalConversation() = desktopE2eTest { _, _, _ ->
        onNodeWithText("azula").assertIsDisplayed()
        onNodeWithText("CONVERSATIONS").assertIsDisplayed()
        // FakeTransport.incoming() surfaces one inbound TERM connection ~300ms after
        // bind(), named from the mnemonic of its fixed "mockterm" remote id -- same
        // conversation e2e/android.yaml waits for.
        waitUntilAtLeastOneExists(hasText("mo · onyx · ivy"), timeoutMillis = 5_000)
    }

    @Test
    fun clickingTheTerminalRowInTheSharedListOpensItsChat() = desktopE2eTest { _, _, _ ->
        // Exercises the desktop sidebar's ConversationList (Sidebar.kt) -- shared
        // with the mobile home list -- end to end: the row renders the live
        // terminal (green dot, via statusDotColor) and its onSelect wiring
        // (Sidebar -> state.selectDesktop) actually opens the chat.
        //
        // Both waits below are real (non-virtual-clock) polls against work that
        // happens off Compose's test clock -- FakeTransport.incoming()'s 300ms
        // delayed emit, then a full click -> recompose -> layout -> draw pass for
        // ChatHeader -- so their timeout is CPU-contention headroom, not protocol
        // time. 5s was tight enough to intermittently throw ComposeTimeoutException
        // under concurrent load (see azula-docs/openspec/specs/testing/design.md, "Known flakes"); 15s gives real
        // margin without masking an actual hang (this conversation is never
        // reconnecting/offline in the mock, so a genuine failure still times out
        // and fails, just slower).
        waitUntilAtLeastOneExists(hasText("mo · onyx · ivy"), timeoutMillis = 20_000)
        onNodeWithText("mo · onyx · ivy").performClick()
        // ChatHeader's status line is tagged "chat-status" (Chat.kt) precisely so
        // this wait keys off that one deterministic node instead of a bare text
        // substring search over the whole tree -- "connected" only ever renders
        // here (rttMs stays null for every FakeTransport stream, so unlike a real
        // transport this line never flips to "direct · Nms · e2e" underneath the
        // wait), but pinning the tag makes the intent explicit and the match cheaper.
        try {
            waitUntilAtLeastOneExists(hasTestTag("chat-status") and hasText("connected"), timeoutMillis = 30_000)
        } catch (e: Throwable) {
            onRoot().printToLog("DEBUG_TREE_ON_FAILURE")
            throw e
        }
    }

    @Test
    fun connectFlowShowsTheLoopbackGreeting() = desktopE2eTest { state, _, _ ->
        connectToMockPeer(state)
    }

    @Test
    fun textRoundTripShowsMyBubbleAndTheLoopbackEcho() = desktopE2eTest { state, _, _ ->
        connectToMockPeer(state)
        onNodeWithTag("composer-input").performTextInput("hello from the e2e suite")
        onNodeWithTag("send-button").performClick()
        waitUntilAtLeastOneExists(hasText("hello from the e2e suite"), timeoutMillis = 5_000)
        waitUntilAtLeastOneExists(hasText("echo · hello from the e2e suite"), timeoutMillis = 5_000)
    }

    @Test
    fun attachMenuListsMediaKindsAndDismissesWithoutPickingPhoto() = desktopE2eTest { state, _, _ ->
        connectToMockPeer(state)
        onNodeWithText("📎").performClick()
        onNodeWithText("Photo").assertIsDisplayed()
        onNodeWithText("Video").assertIsDisplayed()
        onNodeWithText("Audio").assertIsDisplayed()
        onNodeWithText("File").assertIsDisplayed()
        // Dismiss by clicking well outside the popup (the sidebar brand) rather than
        // tapping "Photo" -- picking it would launch a native AWT file dialog that
        // can't be driven from here.
        onNodeWithText("azula").performClick()
        waitUntilDoesNotExist(hasText("Photo"), timeoutMillis = 5_000)
    }

    @Test
    fun sendingMediaRendersTheImageBubbleAndCaption() = desktopE2eTest { state, _, _ ->
        val convId = connectToMockPeer(state)
        // Injected at the picker-callback seam (AttachMenu's onMediaPicked) rather
        // than through a real file-chooser dialog -- see the attach-menu test above
        // for why one can't be driven here. sendMedia is fire-and-forget (it
        // launches on state's own scope internally), so wait for the render rather
        // than assume it landed synchronously.
        state.sendMedia(convId, tinyPngBytes, "e2e.png", "image/png", "cap")
        waitUntilAtLeastOneExists(hasText("cap"), timeoutMillis = 5_000)
        waitUntilExactlyOneExists(hasTestTag("attachment-complete"), timeoutMillis = 5_000)
    }

    @Test
    fun tappingTheImageBubbleOpensAndDismissesTheFullscreenViewer() = desktopE2eTest { state, _, _ ->
        val convId = connectToMockPeer(state)
        state.sendMedia(convId, tinyPngBytes, "e2e.png", "image/png", "cap")
        waitUntilExactlyOneExists(hasTestTag("attachment-complete"), timeoutMillis = 5_000)

        onNodeWithTag("attachment-complete").performClick()
        waitUntilExactlyOneExists(hasTestTag("fullscreen-media"), timeoutMillis = 5_000)

        // The overlay's own content Box is a no-op-clickable that nearly fills the
        // scrim (all but 24dp of padding), so a plain center click on "fullscreen-media"
        // would land on that inert content instead of the dismissing scrim -- click its
        // top-left corner, which is inside the padding and therefore genuinely the scrim.
        onNodeWithTag("fullscreen-media").performTouchInput { click(Offset(0f, 0f)) }
        waitUntilDoesNotExist(hasTestTag("fullscreen-media"), timeoutMillis = 5_000)
    }

    @Test
    fun inboundOfferAutoDownloadsToComplete() = desktopE2eTest { state, transport, scope ->
        connectToMockPeer(state)
        val blobId = "e2e-incoming-1"
        val bytes = ByteArray(2_000) { it.toByte() }
        transport.blobs[blobId] = bytes

        // FakeTransport.injectIncoming is suspend (it pushes onto the loopback's
        // inbox channel) -- dispatch it on the same background scope AzulaState
        // itself runs on, rather than block this synchronous test lambda.
        scope.launch {
            transport.injectIncoming(
                "azula-mock-peer",
                Frame.MediaOffer(id = blobId, kind = "image", name = "incoming.png", mime = "image/png", size = bytes.size.toLong(), fetchTicket = "azula-mock-peer"),
            )
        }

        // THEM bubble appears immediately (Offered), then auto-downloads (images
        // always auto-fetch regardless of size -- see MediaService.onMediaOffer) --
        // wait straight for Complete rather than assert the intermediate state, since
        // a small blob like this can race past Offered/Downloading before this test
        // ever gets to look. This leg chains several real (non-virtual-clock) async
        // hops -- inject -> onMediaOffer -> startFetch -> FakeMediaStream chunks ->
        // Complete -> recompose -- entirely off Dispatchers.Default, so it's the
        // most load-sensitive wait in this suite; 8s still intermittently threw
        // ComposeTimeoutException under concurrent CPU load (see testing/design.md, "Known flakes"), so
        // this uses the same 20s headroom as the other timing-sensitive waits here.
        waitUntilExactlyOneExists(hasTestTag("attachment-complete"), timeoutMillis = 20_000)
    }

    @Test
    fun invitesFlowMintsAndListsAnInvite() = desktopE2eTest { _, _, _ ->
        // Sidebar "invites" row -> InvitesPane, initially empty.
        onNodeWithText("invites").performClick()
        onNodeWithText("No invites yet — mint one from the connect screen to bring in a peer.").assertIsDisplayed()

        // Back to connect: desktop mints inline (useBottomSheetForCreateInvite =
        // false there -- see ConnectScreen), not through the mobile bottom sheet.
        onNode(hasText("connect a peer") and hasClickAction()).performClick()
        onNodeWithText("+ create invite").performClick()
        onNodeWithText("create invite").performClick()
        waitUntilAtLeastOneExists(hasText("copy invite link"), timeoutMillis = 5_000)

        // The minted invite now shows up in "My invites".
        onNodeWithText("invites").performClick()
        waitUntilAtLeastOneExists(hasText("expires in", substring = true), timeoutMillis = 5_000)
    }
}

/** A minimal, genuinely valid 1x1 transparent PNG -- decodes cleanly through
 *  whatever image codec `decodeImageBitmap` uses on this platform, unlike an
 *  arbitrary byte blob. */
private val tinyPngBytes: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
)
