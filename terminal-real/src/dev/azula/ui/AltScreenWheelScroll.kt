package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Desktop mouse-wheel support for [AltScreen]'s swipe/wheel-to-arrow-keys
 * scrolling (see `sendScrollArrow`) and its mouse-tracking-mode wheel reporting
 * (see `sendMouseReport`): [onScroll] is called with a signed notch/line count --
 * positive/negative encode the same "up"/"down" direction the drag gesture in
 * [AltScreen] uses -- already multiplied by [WHEEL_LINES_PER_NOTCH] and clamped
 * to [ALT_SCROLL_RATE_CAP], plus the pointer position (in this modifier's own
 * local coordinate space) the notch occurred at, for encoding a positioned
 * mouse-wheel report.
 *
 * Platform-split (like [RawTerminalInput]): `onPointerEvent`'s
 * `PointerEventType.Scroll` + `scrollDelta` are desktop-only Compose UI APIs,
 * unresolved on the Android/iOS targets -- there, this is a no-op, since touch
 * scrolling already goes through [AltScreen]'s vertical-drag gesture (and
 * there's no mouse to report anyway).
 */
@Composable
internal expect fun Modifier.altScreenWheelScroll(onScroll: (lines: Int, position: Offset) -> Unit): Modifier
