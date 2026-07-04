package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop mouse-wheel support for [AltScreen]'s swipe/wheel-to-arrow-keys
 * scrolling (see `sendScrollArrow`): [onScrollLines] is called with a signed
 * line count -- positive/negative encode the same "up"/"down" direction the
 * drag gesture in [AltScreen] uses -- already multiplied by
 * [WHEEL_LINES_PER_NOTCH] and clamped to [ALT_SCROLL_RATE_CAP].
 *
 * Platform-split (like [RawTerminalInput]): `onPointerEvent`'s
 * `PointerEventType.Scroll` + `scrollDelta` are desktop-only Compose UI APIs,
 * unresolved on the Android/iOS targets -- there, this is a no-op, since touch
 * scrolling already goes through [AltScreen]'s vertical-drag gesture.
 */
@Composable
internal expect fun Modifier.altScreenWheelScroll(onScrollLines: (Int) -> Unit): Modifier
