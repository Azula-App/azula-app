package dev.azula.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.min

actual fun scaleImageForAvatar(bytes: ByteArray, maxDim: Int): ScaledImage? = runCatching {
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scale = min(maxDim.toFloat() / src.width, maxDim.toFloat() / src.height).coerceAtMost(1f)
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(src, w, h, true)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    ScaledImage(out.toByteArray(), "image/jpeg")
}.getOrNull()
