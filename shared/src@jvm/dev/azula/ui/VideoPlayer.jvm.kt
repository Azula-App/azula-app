package dev.azula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.AzulaColors
import dev.azula.theme.Fonts
import java.awt.Desktop
import java.io.File

/**
 * Desktop Compose has no lightweight inline video widget, so this is a poster +
 * button that opens [path] in the system's default player (matching the design
 * doc's "poster + open in system player" desktop policy — a deliberate ship
 * state, not a stopgap).
 */
@Composable
actual fun VideoPlayer(path: String, modifier: Modifier) {
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            .background(AzulaColors.codeBlock).border(1.dp, AzulaColors.border4, RoundedCornerShape(10.dp))
            .clickable { runCatching { Desktop.getDesktop().open(File(path)) } },
        contentAlignment = Alignment.Center,
    ) {
        Text("▶ open in player", color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontSize = 13.sp)
    }
}
