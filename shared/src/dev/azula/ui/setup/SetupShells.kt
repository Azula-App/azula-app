package dev.azula.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.azula.theme.AzulaColors
import dev.azula.theme.AzulaShapes
import dev.azula.theme.Fonts
import dev.azula.theme.azulaCard
import dev.azula.ui.Brand

/**
 * The rail's four stages (identity / back up / persona / done) -- shared by
 * the wide step rail and the compact progress dots, per the Adaptive Setup
 * Presentation requirement. [SetupStep.RESTORE] isn't its own stage: it maps
 * to "identity" alongside [SetupStep.FORK], since restoring is still
 * resolving that same first stage (the rail shows identity/back up/persona/
 * done, not a 5th "restore" item).
 */
private val railLabels = listOf("identity", "back up", "persona", "done")

private fun railIndexFor(step: SetupStep): Int = when (step) {
    SetupStep.FORK, SetupStep.RESTORE -> 0
    SetupStep.BACKUP -> 1
    SetupStep.PERSONA -> 2
}

private enum class RailItemState { COMPLETED, CURRENT, PENDING }

/**
 * Wide (>=820dp) shell, blueprint 1c: a step rail (identity / back up /
 * persona / done, each completed/current/pending) beside a panel holding the
 * current step's content.
 */
@Composable
internal fun SetupWideShell(step: SetupStep, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.width(220.dp).fillMaxHeight()) {
            Brand()
            Spacer(Modifier.height(28.dp))
            SetupRail(step)
        }
        Box(Modifier.weight(1f).fillMaxHeight().azulaCard().padding(32.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

@Composable
private fun SetupRail(step: SetupStep) {
    val current = railIndexFor(step)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        railLabels.forEachIndexed { i, label ->
            val itemState = when {
                i < current -> RailItemState.COMPLETED
                i == current -> RailItemState.CURRENT
                else -> RailItemState.PENDING
            }
            RailItem(label, itemState)
        }
    }
}

@Composable
private fun RailItem(label: String, state: RailItemState) {
    Row(
        Modifier.fillMaxWidth().clip(AzulaShapes.rMd)
            .background(if (state == RailItemState.CURRENT) AzulaColors.primaryWash else Color.Transparent)
            .border(1.dp, if (state == RailItemState.CURRENT) AzulaColors.primaryEdge else Color.Transparent, AzulaShapes.rMd)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            when (state) {
                RailItemState.COMPLETED -> "✓"
                RailItemState.CURRENT -> "›"
                RailItemState.PENDING -> "·"
            },
            color = when (state) {
                RailItemState.COMPLETED -> AzulaColors.online
                RailItemState.CURRENT -> AzulaColors.pinkLight
                RailItemState.PENDING -> AzulaColors.textFaint2
            },
            fontFamily = Fonts.mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Text(
            label,
            color = when (state) {
                RailItemState.COMPLETED -> AzulaColors.text2
                RailItemState.CURRENT -> AzulaColors.pinkLight
                RailItemState.PENDING -> AzulaColors.textFaint2
            },
            fontFamily = Fonts.ui,
            fontWeight = if (state == RailItemState.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
        )
    }
}

/**
 * Compact (<820dp) shell, blueprint 1a: a full-screen stepped card per step,
 * with the [Brand] lockup as header and progress dots indicating position.
 */
@Composable
internal fun SetupCompactShell(step: SetupStep, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Brand()
        Spacer(Modifier.height(18.dp))
        ProgressDots(current = railIndexFor(step))
        Spacer(Modifier.height(18.dp))
        Box(Modifier.weight(1f).fillMaxWidth().azulaCard().padding(20.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

@Composable
private fun ProgressDots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        railLabels.indices.forEach { i ->
            Box(
                Modifier.size(if (i == current) 8.dp else 6.dp).clip(AzulaShapes.rPill)
                    .background(if (i <= current) AzulaColors.pink else AzulaColors.border4),
            )
        }
    }
}
