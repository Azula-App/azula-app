import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.azula.AzulaApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "azula — peer to peer",
        state = rememberWindowState(width = 1180.dp, height = 760.dp),
    ) {
        AzulaApp()
    }
}
