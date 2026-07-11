package dev.azula.ui

import kotlinx.coroutines.runBlocking
import shared.generated.resources.Res
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * The native desktop chrome Compose's `Window` can't set for us: the macOS
 * appearance (which colors the title bar and its close/zoom buttons) and the
 * Dock icon. Call it from `main` before the first window — see
 * [applyMacAppearance] for why the ordering matters.
 *
 * [dockIcon] defaults to the shipping icon; the `-mock` app passes its own.
 */
fun applyDesktopChrome(appName: String = "Azula", dockIcon: Image? = appIconImage()) {
    applyMacAppearance(appName)
    applyDockIcon(dockIcon)
}

/** The bundled app-icon bitmap as an AWT image — the Compose equivalent is [appIconPainter]. */
fun appIconImage(): Image? = runCatching {
    ImageIO.read(runBlocking { Res.readBytes("drawable/app_icon.png") }.inputStream())
}.getOrNull()

/**
 * AppKit reads these off the system properties when it starts up, so they only land if
 * this runs before the first AWT/Compose window (and before [applyDockIcon], which
 * initializes the toolkit). Without the name, the menu bar reads "MainKt" — the entry
 * point's class. AzulaTheme is dark-only, so pin the dark appearance rather than
 * "system": under a light macOS theme the native title bar (and the strip holding the
 * close/minimize/zoom buttons) would otherwise render white above the app's black background.
 */
private fun applyMacAppearance(appName: String) {
    if (!isMac) return
    System.setProperty("apple.awt.application.name", appName)
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

/**
 * `Window(icon = ...)` only reaches the AWT window icon, which is what Windows and
 * Linux draw but macOS ignores — there the Dock/⌘-Tab icon comes from the Taskbar (or
 * from an .app bundle, which a `./kotlin run` has none of). Taskbar is unsupported on
 * some platforms and headless JVMs; the window icon still stands in there.
 */
private fun applyDockIcon(icon: Image?) {
    if (icon == null) return
    runCatching {
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
        taskbar.iconImage = if (isMac) onAppleIconGrid(icon) else icon
    }
}

/** Apple's icon grid: the artwork occupies 824x824 of a 1024x1024 canvas, transparent around it. */
private const val ICON_CANVAS = 1024
private const val ICON_ARTWORK = 824

/**
 * macOS hands the Dock icon straight through — no mask, no shadow, and no inset — but
 * every native icon it sits beside is drawn on the grid above, i.e. with ~10% transparent
 * margin on each side. app_icon.png is full-bleed (rightly so: it's also the Windows/Linux
 * window icon, where nothing inserts a margin), so scale it onto the grid here. Skip this
 * and azula renders edge-to-edge in its tile, visibly larger than every neighbor.
 */
private fun onAppleIconGrid(icon: Image): Image {
    val canvas = BufferedImage(ICON_CANVAS, ICON_CANVAS, BufferedImage.TYPE_INT_ARGB)
    val g = canvas.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    val inset = (ICON_CANVAS - ICON_ARTWORK) / 2
    g.drawImage(icon, inset, inset, ICON_ARTWORK, ICON_ARTWORK, null)
    g.dispose()
    return canvas
}
