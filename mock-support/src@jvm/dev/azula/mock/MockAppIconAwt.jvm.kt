package dev.azula.mock

import kotlinx.coroutines.runBlocking
import mock_support.generated.resources.Res
import java.awt.Image
import javax.imageio.ImageIO

/**
 * The inverted `-mock` icon as an AWT image, for the desktop Dock — the Compose
 * equivalent is [mockAppIconPainter]. Handed to `applyDesktopChrome()` by
 * jvm-app-mock so the mock build wears the light-ground mark in the dock too,
 * and stays tellable apart from the real app.
 *
 * Named `MockAppIconAwt`, not `MockAppIcon.jvm.kt`: a `src@jvm` file whose base name
 * matches a common-source one (MockAppIcon.kt) reads as an expect/actual pair to
 * Amper, and it then generates this module's compose-resource accessors empty —
 * `Res.drawable.app_icon_mock` stops resolving. Keep the names distinct.
 */
fun mockAppIconImage(): Image? = runCatching {
    ImageIO.read(runBlocking { Res.readBytes("drawable/app_icon_mock.png") }.inputStream())
}.getOrNull()
