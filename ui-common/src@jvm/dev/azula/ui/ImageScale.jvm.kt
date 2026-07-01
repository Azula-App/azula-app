package dev.azula.ui

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.min

actual fun scaleImageForAvatar(bytes: ByteArray, maxDim: Int): ScaledImage? = runCatching {
    val src = Image.makeFromEncoded(bytes)
    val scale = min(maxDim.toFloat() / src.width, maxDim.toFloat() / src.height).coerceAtMost(1f)
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(src, Rect.makeWH(w.toFloat(), h.toFloat()))
    val snapshot = surface.makeImageSnapshot()
    val data = snapshot.encodeToData(EncodedImageFormat.JPEG, 85) ?: return null
    ScaledImage(data.bytes, "image/jpeg")
}.getOrNull()
