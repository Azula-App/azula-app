package dev.azula.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.azula.model.Attachment
import dev.azula.model.MediaKind
import dev.azula.state.AzulaState
import dev.azula.state.FullscreenMedia

/**
 * Root-level fullscreen viewer for [AzulaState.fullscreenMedia]: a dark scrim
 * behind a [HorizontalPager] over [FullscreenMedia.items] (all completed images
 * in the conversation, for an image; a single item for video). Each image page
 * supports pinch-to-zoom + pan (clamped to bounds) and double-tap zoom; a single
 * tap (or Esc) dismisses back to chat. Swiping between pages is gated to only
 * work when the current page is unzoomed, so panning a zoomed image doesn't
 * fight the pager. Mounted once at the root of both MobileApp and DesktopApp, on
 * top of whatever screen is showing — deliberately not an [dev.azula.state.MScreen]
 * case, since it's an overlay rather than a navigation destination.
 */
@Composable
fun FullscreenMediaOverlay(state: AzulaState) {
    val fm = state.fullscreenMedia ?: return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(fm) { focusRequester.requestFocus() }

    val pagerState = rememberPagerState(initialPage = fm.startIndex) { fm.items.size }
    // Hoisted zoom state shared by whichever page is current; reset whenever the
    // page changes so swiping away from a zoomed image doesn't carry the zoom
    // over, and so the pager knows (via userScrollEnabled below) whether the
    // active page is currently zoomed in.
    val scaleState = remember(fm) { mutableStateOf(1f) }
    val offsetState = remember(fm) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scaleState.value = 1f
        offsetState.value = Offset.Zero
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color(0xE6000000))
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent {
                if (it.key == Key.Escape) { state.dismissFullscreenMedia(); true } else false
            }
            .testTag("fullscreen-media"),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scaleState.value <= 1f,
            key = { fm.items[it].id },
        ) { page ->
            val att = fm.items[page]
            when (att.kind) {
                MediaKind.VIDEO -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    att.blobId?.let { state.blobPath(it) }?.let { path ->
                        VideoPlayer(path, Modifier.fillMaxWidth(0.92f).aspectRatio(16f / 9f))
                    }
                }
                else -> FullscreenImage(
                    state = state,
                    att = att,
                    scaleState = scaleState,
                    offsetState = offsetState,
                    onDismiss = { state.dismissFullscreenMedia() },
                )
            }
        }
    }
}

@Composable
private fun FullscreenImage(
    state: AzulaState,
    att: Attachment,
    scaleState: MutableState<Float>,
    offsetState: MutableState<Offset>,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(att.id) {
        (att.blobId?.let { state.blobBytes(it, maxBytes = 64L * 1024 * 1024) } ?: att.bytes)?.let { decodeImageBitmap(it) }
    }
    if (bitmap == null) {
        Box(
            Modifier.fillMaxSize().pointerInput(att.id) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(att.name, color = Color.White.copy(alpha = 0.7f))
        }
        return
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scale by scaleState
    val offset by offsetState

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(att.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scaleState.value * zoom).coerceIn(1f, 5f)
                    val maxX = (containerSize.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                    val maxY = (containerSize.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                    val moved = if (newScale > 1f) offsetState.value + pan else Offset.Zero
                    scaleState.value = newScale
                    offsetState.value = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(att.id) {
                detectTapGestures(
                    onDoubleTap = {
                        scaleState.value = if (scaleState.value > 1f) 1f else 2.5f
                        offsetState.value = Offset.Zero
                    },
                    onTap = { onDismiss() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap,
            contentDescription = att.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
