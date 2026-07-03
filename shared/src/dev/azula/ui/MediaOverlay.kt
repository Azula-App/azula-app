package dev.azula.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.azula.model.Attachment
import dev.azula.model.MediaKind
import dev.azula.state.AzulaState

/**
 * Root-level fullscreen viewer for [AzulaState.fullscreenMedia]: a dark scrim
 * behind a centered image (its full body, lazily decoded via
 * [AzulaState.blobBytes]) or a [VideoPlayer] (its body from [AzulaState.blobPath]).
 * Mounted once at the root of both MobileApp and DesktopApp, on top of whatever
 * screen is showing — deliberately not an [dev.azula.state.MScreen] case, since
 * it's an overlay rather than a navigation destination. Tapping the scrim, or
 * pressing Esc, dismisses it.
 */
@Composable
fun FullscreenMediaOverlay(state: AzulaState) {
    val att = state.fullscreenMedia ?: return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(att.id) { focusRequester.requestFocus() }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xE6000000))
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent {
                if (it.key == Key.Escape) { state.dismissFullscreenMedia(); true } else false
            }
            .clickable { state.dismissFullscreenMedia() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // A no-op clickable, just to swallow taps on the content itself so
            // they don't fall through to the scrim's dismiss handler above.
            Modifier.padding(24.dp).clickable {},
            contentAlignment = Alignment.Center,
        ) {
            when (att.kind) {
                MediaKind.VIDEO -> att.blobId?.let { state.blobPath(it) }?.let { path ->
                    VideoPlayer(path, Modifier.fillMaxWidth(0.92f).aspectRatio(16f / 9f))
                }
                else -> FullscreenImage(state, att)
            }
        }
    }
}

@Composable
private fun FullscreenImage(state: AzulaState, att: Attachment) {
    val bitmap = remember(att.id) {
        (att.blobId?.let { state.blobBytes(it, maxBytes = 64L * 1024 * 1024) } ?: att.bytes)?.let { decodeImageBitmap(it) }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = att.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}
