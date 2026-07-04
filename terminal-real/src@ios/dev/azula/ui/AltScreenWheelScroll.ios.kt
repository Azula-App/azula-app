package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No mouse wheel on iOS -- a trackpad (iPad + a Magic Trackpad, e.g.) could in
 *  principle drive this, but that's unexplored territory for now. Alt-screen
 *  scrolling goes through the vertical-drag gesture instead (see `AltScreen`). */
@Composable
internal actual fun Modifier.altScreenWheelScroll(onScrollLines: (Int) -> Unit): Modifier = this
