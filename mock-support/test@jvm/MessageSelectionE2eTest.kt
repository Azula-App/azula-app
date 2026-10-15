@file:OptIn(
    androidx.compose.ui.test.ExperimentalTestApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import dev.azula.mock.FakeTransport
import dev.azula.model.Message
import dev.azula.model.Role
import dev.azula.theme.AzulaTheme
import dev.azula.ui.MessageBubble
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A chat bubble's text is selectable and copyable. [MessageBubble] wraps its
 * Markdown body in a `SelectionContainer` (tagged `message-text`); this is the
 * regression guard against that wrapper being dropped, or the text being put
 * behind a `DisableSelection`, since nothing else in the suite would notice --
 * the bubble renders identically either way.
 *
 * Desktop selects with a mouse drag (touch platforms use long-press, which the
 * same container provides -- the JVM suite owns shared UI behaviour, see
 * openspec/specs/testing). The selection is observed through its only
 * user-visible output: the copy shortcut writing to the clipboard, via the
 * [LocalClipboard] override [SelectionClipboard]. (SelectionContainer reads
 * `LocalClipboard`, not the deprecated `LocalClipboardManager`, so overriding
 * the latter would capture nothing.)
 */
class MessageSelectionE2eTest {

    /** Mounts one [MessageBubble] for [message] and runs [block] with the clipboard it writes to. */
    private fun bubbleTest(message: Message, block: ComposeUiTest.(SelectionClipboard) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runComposeUiTest {
                val state = testAzulaState(scope, FakeTransport())
                val clipboard = SelectionClipboard()
                setContent {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        AzulaTheme { MessageBubble(state, "conv", message) }
                    }
                }
                waitUntilAtLeastOneExists(hasTestTag("message-text"), timeoutMillis = 30_000)
                block(clipboard)
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Drags from near the top-left to near the bottom-right of the `message-text` node
     * (so a multi-block message is covered end to end), then fires the platform copy
     * shortcut -- Command on macOS, Control elsewhere. Returns what reached the clipboard.
     *
     * The drag is stepped with the event clock advancing between moves, like a real
     * mouse. A single press -> jump -> release is NOT enough: the selection gesture
     * ignores it (measured: no highlight, nothing copied), which reads as "selection
     * is broken" when it is only the injected gesture that is too coarse.
     */
    private fun ComposeUiTest.dragSelectAndCopy(clipboard: SelectionClipboard): String {
        onNodeWithTag("message-text").performMouseInput {
            val steps = 12
            moveTo(Offset(3f, height / 4f))
            press()
            for (i in 1..steps) {
                advanceEventTime(30)
                moveTo(Offset(3f + (width - 6f) * i / steps, height / 4f + (height / 2f) * i / steps))
            }
            advanceEventTime(30)
            release()
        }
        waitForIdle()
        val modifier = if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag("message-text").performKeyInput {
            keyDown(modifier)
            keyDown(Key.C)
            keyUp(Key.C)
            keyUp(modifier)
        }
        // The copy handler is a suspend call on the clipboard; give it the chance to land.
        waitUntil(timeoutMillis = 5_000) { clipboard.text != null }
        return clipboard.text.orEmpty()
    }

    @Test
    fun aPeersMessageTextCanBeSelectedAndCopied() {
        bubbleTest(Message(id = "m1", role = Role.THEM, text = "selectable peer message")) { clipboard ->
            val copied = dragSelectAndCopy(clipboard)
            assertTrue(copied.contains("selectable") && copied.contains("message"), "dragging over the bubble and copying must put its text on the clipboard, got '$copied'")
        }
    }

    @Test
    fun myMessageTextCanBeSelectedAndCopied() {
        bubbleTest(Message(id = "m2", role = Role.ME, text = "selectable message of mine")) { clipboard ->
            val copied = dragSelectAndCopy(clipboard)
            assertTrue(copied.contains("selectable") && copied.contains("mine"), "got '$copied'")
        }
    }

    @Test
    fun selectionSpansTheBlocksOfOneMarkdownMessage() {
        // Two paragraphs are two Text nodes inside one container; a drag from the
        // first to the last must pick up both, i.e. the whole message is one selection scope.
        bubbleTest(Message(id = "m3", role = Role.AZULA, text = "first paragraph\n\nsecond paragraph")) { clipboard ->
            val copied = dragSelectAndCopy(clipboard)
            assertTrue(copied.contains("paragraph") && copied.contains("second"), "got '$copied'")
        }
    }
}

/** Captures what the UI copied; [text] is its plain-text content (null until something is written). */
private class SelectionClipboard : Clipboard {
    @Volatile
    private var entry: ClipEntry? = null

    val text: String?
        get() = entry?.asAwtTransferable?.let { t ->
            if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) t.getTransferData(DataFlavor.stringFlavor) as? String else null
        }

    override suspend fun getClipEntry(): ClipEntry? = entry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    override val nativeClipboard: Any get() = this
}
