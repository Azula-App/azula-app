package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.altScreenWheelScroll(onScrollLines: (Int) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Scroll) {
        val delta = it.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (delta == 0f) return@onPointerEvent
        // Negative scrollDelta.y == wheel notched "up" -- mapped to the same
        // "reveal content above, like `less`" direction as AltScreen's drag
        // gesture; flip this sign if a platform's wheel convention disagrees.
        val lines = (-delta * WHEEL_LINES_PER_NOTCH).toInt().coerceIn(-ALT_SCROLL_RATE_CAP, ALT_SCROLL_RATE_CAP)
        if (lines != 0) {
            onScrollLines(lines)
            it.changes.firstOrNull()?.consume()
        }
    }
