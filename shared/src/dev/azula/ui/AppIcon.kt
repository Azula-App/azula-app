package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import shared.generated.resources.Res
import shared.generated.resources.app_icon

/**
 * The bundled app-icon bitmap, exposed publicly so the desktop entry point
 * (jvm-app) can set it as the window/dock icon. The generated [Res] is internal
 * to this module, so callers in other modules go through this helper.
 */
@Composable
fun appIconPainter(): Painter = painterResource(Res.drawable.app_icon)
