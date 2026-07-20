package dev.azula.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** The azula corner-radius scale. See design-system.md §6. */
object AzulaShapes {
    val rXs = RoundedCornerShape(5.dp)
    val rSm = RoundedCornerShape(8.dp)
    val rMd = RoundedCornerShape(10.dp)   // was 9 in A2uiTokens
    val rLg = RoundedCornerShape(13.dp)   // was 12 in A2uiTokens
    val rXl = RoundedCornerShape(16.dp)
    val rPill = RoundedCornerShape(percent = 50)

    // Chat-only asymmetric bubble corners
    val rBubbleMe = RoundedCornerShape(15.dp, 15.dp, 5.dp, 15.dp)
    val rBubbleThem = RoundedCornerShape(15.dp, 15.dp, 15.dp, 5.dp)
}
