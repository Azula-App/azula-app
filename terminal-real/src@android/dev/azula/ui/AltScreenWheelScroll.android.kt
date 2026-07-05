package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** No traditional mouse wheel on Android touch input -- alt-screen scrolling
 *  goes through the vertical-drag gesture instead (see `AltScreen`). */
@Composable
internal actual fun Modifier.altScreenWheelScroll(onScroll: (lines: Int, position: Offset) -> Unit): Modifier = this
