import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.azula.AzulaApp
import dev.azula.link.DeepLinkBus
import dev.azula.mock.installMockState
import dev.azula.mock.mockAppIconPainter

fun main(args: Array<String>) {
    // -mock build: install an AzulaState wired to the fakes (FakeTransport + no
    // persistence) before the composition, so rememberAzulaState() returns it and
    // the real iroh transport is never created.
    installMockState()

    // Test/automation hook: auto-dial a peer ticket on launch (here it hits the
    // fake loopback). The bus buffers the token until AzulaState.start() registers.
    System.getenv("AZULA_CONNECT")?.takeIf { it.isNotBlank() }?.let { DeepLinkBus.deliver(it) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "azula — mock",
            icon = mockAppIconPainter(),
            state = rememberWindowState(width = 1180.dp, height = 760.dp),
        ) {
            AzulaApp()
        }
    }
}
