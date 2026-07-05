package dev.azula.a2ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.Fonts
import kotlin.math.abs
import kotlin.math.sin

/**
 * The shared "neon-glass" audio waveform bar -- a play/pause circle, a 40-bar
 * pseudo-waveform tinted up to the played fraction, and a mono `mm:ss / mm:ss`
 * timestamp. Lifted verbatim (layout/sizing/colors) from the old [RenderAudio]
 * mock, now driven by real playback state -- from [dev.azula.ui.rememberAudioPlayer]
 * (chat's [dev.azula.ui.AudioAttachmentView]) or the a2ui `AudioPlayer` component
 * ([RenderAudio]) -- instead of the mock's hardcoded numbers.
 *
 * When [onSeek] is null the waveform row is inert (the old mock's behavior);
 * when non-null, tapping or horizontally dragging across the bars seeks
 * proportionally to [durationMs]. [enabled] gates both the play button and the
 * seek gesture (e.g. while a component tree is frozen).
 */
@Composable
fun A2uiAudioBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggle: () -> Unit,
    onSeek: ((Long) -> Unit)? = null,
    seed: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier
            .fillMaxWidth()
            .clip(A2uiTokens.rLg)
            .background(A2uiTokens.surfaceAlt)
            .border(1.dp, A2uiTokens.outlineSoft, A2uiTokens.rLg)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(A2uiTokens.primaryBrush)
                .let { if (enabled) it.clickable(onClick = onToggle) else it },
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) PauseBars(11.dp, A2uiTokens.onPrimary) else PlayTriangle(11.dp, A2uiTokens.onPrimary)
        }
        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 13.dp)
                .height(30.dp)
                .let { row ->
                    if (enabled && onSeek != null) {
                        row
                            .pointerInput(durationMs) {
                                detectTapGestures { offset ->
                                    if (durationMs > 0) onSeek(seekMs(offset.x, size.width, durationMs))
                                }
                            }
                            .pointerInput(durationMs) {
                                detectHorizontalDragGestures { change, _ ->
                                    if (durationMs > 0) onSeek(seekMs(change.position.x, size.width, durationMs))
                                }
                            }
                    } else row
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val bars = 40
            repeat(bars) { i ->
                val h = (6f + abs(sin((i + seed) * 0.9)) * 22f).toInt()
                val filled = i.toFloat() / bars < fraction
                Box(Modifier.width(3.dp).height(h.dp).clip(A2uiTokens.rPill).background(if (filled) A2uiTokens.primary else A2uiTokens.trackIdle))
            }
        }
        Text("${formatAudioMs(positionMs)} / ${formatAudioMs(durationMs)}", color = A2uiTokens.muted, fontFamily = Fonts.mono, fontSize = 11.sp)
    }
}

/** The playback position (ms, clamped into [0, durationMs]) that an `x` pixel
 *  offset across a [width]-pixel-wide waveform row corresponds to. */
private fun seekMs(x: Float, width: Int, durationMs: Long): Long {
    val fraction = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
    return (fraction * durationMs).toLong().coerceIn(0L, durationMs)
}

@Composable
private fun PauseBars(dim: Dp, color: Color) {
    Canvas(Modifier.size(dim)) {
        val barWidth = size.width * 0.28f
        drawRect(color, topLeft = Offset(0f, 0f), size = Size(barWidth, size.height))
        drawRect(color, topLeft = Offset(size.width - barWidth, 0f), size = Size(barWidth, size.height))
    }
}

private fun formatAudioMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
