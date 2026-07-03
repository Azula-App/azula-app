package dev.azula.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.azula.link.Qr
import dev.azula.theme.AzulaColors

/**
 * A real, scannable QR of [data] — hand-rolled encoder (see [Qr]). Dark modules
 * on a light card (high luminance contrast is what camera scanners need; the
 * brand pink-on-dark would not reliably decode). Used by the connect screen's
 * own-code QR (Connect.kt) and the minted-invite QR (ShareInvite.kt).
 */
@Composable
fun QrCode(data: String) {
    val modules = remember(data) { Qr.encode(data, Qr.Ecc.MEDIUM) }
    val n = modules.size
    val dark = Color(0xFF070709) // brand near-black
    Box(
        Modifier.size(168.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .border(1.dp, AzulaColors.border4, RoundedCornerShape(12.dp)),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            // Reserve a quiet zone (~4 modules of white) so the finder patterns read cleanly.
            val quiet = 4
            val cell = size.width / (n + quiet * 2)
            val origin = cell * quiet
            for (y in 0 until n) for (x in 0 until n) {
                if (modules[y][x]) {
                    drawRect(
                        color = dark,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell + 0.5f, cell + 0.5f), // slight overdraw kills seams
                    )
                }
            }
        }
    }
}
