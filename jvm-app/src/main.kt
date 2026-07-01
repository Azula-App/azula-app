import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.azula.AzulaApp
import dev.azula.link.DeepLinkBus
import dev.azula.ui.appIconPainter

fun main(args: Array<String>) = application {
    // Demo/UI-test build: seed content (off by default → shipping build is empty).
    val isDemo = System.getenv("AZULA_DEMO") == "1" || args.contains("--demo")
    // Test/automation hook: auto-dial a peer ticket on launch (over iroh). The
    // bus buffers the token until AzulaState.start() registers its handler.
    System.getenv("AZULA_CONNECT")?.takeIf { it.isNotBlank() }?.let { DeepLinkBus.deliver(it) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "azula — peer to peer",
        icon = appIconPainter(),
        state = rememberWindowState(width = 1180.dp, height = 760.dp),
    ) {
        AzulaApp(isDemo)
    }
}
