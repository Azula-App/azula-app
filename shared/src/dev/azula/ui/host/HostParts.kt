package dev.azula.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaGlow
import dev.azula.theme.azulaMutedPanel
import dev.azula.ui.PinkButtonBrush
import dev.azula.ui.StatusDot

/** The status of one checklist or stepper line. */
internal enum class LineState { DONE, CURRENT, PENDING, FAILED }

internal fun lineColor(s: LineState): Color = when (s) {
    LineState.DONE -> AzulaColors.online
    LineState.CURRENT -> AzulaColors.pink
    LineState.PENDING -> AzulaColors.textFaint2
    LineState.FAILED -> AzulaColors.error
}

/** A section label in the settings style (mono, dim, caps). */
@Composable
internal fun HostLabel(text: String) {
    Text(text, color = AzulaColors.textDim2, fontFamily = Fonts.mono, fontSize = 11.sp)
}

@Composable
internal fun HostBody(text: String, color: Color = AzulaColors.textDim2, tag: String? = null) {
    Text(
        text, color = color, fontFamily = Fonts.ui, fontSize = 12.sp, lineHeight = 17.sp,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

@Composable
internal fun HostMono(text: String, color: Color = AzulaColors.textDim2, size: Int = 11, tag: String? = null) {
    Text(
        text, color = color, fontFamily = Fonts.mono, fontSize = size.sp,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

/** The primary button with its glow halo (design-system §7.5), or the secondary input-surface variant. */
@Composable
internal fun HostButton(text: String, tag: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val base = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).testTag(tag)
    val surface = if (primary) {
        base.azulaGlow().clip(AzulaShapes.rMd).background(PinkButtonBrush)
    } else {
        base.clip(AzulaShapes.rMd).background(AzulaColors.inputBg).border(1.dp, AzulaColors.border4, AzulaShapes.rMd)
    }
    Box(surface.clickable(enabled = enabled) { onClick() }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = if (primary) Color.White else AzulaColors.pinkLight,
            fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
    }
}

/** A small inline action (a checklist line's button), narrower than [HostButton]. */
@Composable
internal fun HostChipButton(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(AzulaShapes.rSm).background(AzulaColors.primarySelected)
            .border(1.dp, AzulaColors.primaryEdge, AzulaShapes.rSm)
            .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 6.dp).testTag(tag),
    ) {
        Text(text, color = AzulaColors.pinkLight, fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/** One dot + title + optional detail + optional action, as in S2's checklist and S3's stepper. */
@Composable
internal fun HostLine(
    state: LineState,
    title: String,
    tag: String,
    detail: String? = null,
    detailColor: Color = AzulaColors.textDim2,
    action: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.padding(top = 5.dp)) { StatusDot(lineColor(state)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = if (state == LineState.PENDING) AzulaColors.textDim else AzulaColors.text0,
                fontFamily = Fonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            )
            if (detail != null) {
                Text(detail, color = detailColor, fontFamily = Fonts.mono, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (action != null) {
                Spacer(Modifier.width(1.dp))
                action()
            }
        }
    }
}

/** A muted panel row with a title, a mono subtitle and a trailing control: the S0 row and S4's toggle row. */
@Composable
internal fun HostPanelRow(tag: String, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().azulaMutedPanel().padding(13.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) { content() }
}
