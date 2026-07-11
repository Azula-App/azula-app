import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.azula.AzulaApp
import dev.azula.link.DeepLinkBus
import dev.azula.ui.appIconPainter
import dev.azula.ui.applyDesktopChrome

fun main(args: Array<String>) {
    // Dark title bar + dock icon. Has to run before the first Window: macOS reads
    // the appearance when AppKit starts (see applyDesktopChrome).
    applyDesktopChrome()
    application {
        // This is the shipping build (real transport, no seeded content) -- the
        // `-mock` apps (mock-support/src/dev/azula/mock/MockApp.kt) are the ones
        // that launch pre-populated, over a FakeTransport + SeededMessageStore.
        // Test/automation hook: auto-dial a peer ticket on launch (over iroh). The
        // bus buffers the token until AzulaState.start() registers its handler.
        System.getenv("AZULA_CONNECT")?.takeIf { it.isNotBlank() }?.let { DeepLinkBus.deliver(it) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Azula — peer to peer",
            icon = appIconPainter(),
            state = rememberWindowState(width = 1180.dp, height = 760.dp),
        ) {
            AzulaApp()
        }
    }
}
