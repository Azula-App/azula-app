package dev.azula.mock

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import mock_support.generated.resources.Res
import mock_support.generated.resources.app_icon_mock
import org.jetbrains.compose.resources.painterResource

/**
 * The inverted app icon — the real mark on a light ground — for the desktop
 * `-mock` window/dock icon. It lives here rather than in `shared` so the mock
 * asset never ships inside the real app, mirroring how the fakes are kept out of
 * it. Regenerate with `design/icon/generate.sh` (its `invert` pass); the
 * generated [Res] is internal to this module, so jvm-app-mock goes through this
 * helper, exactly as jvm-app goes through [dev.azula.ui.appIconPainter].
 */
@Composable
fun mockAppIconPainter(): Painter = painterResource(Res.drawable.app_icon_mock)
