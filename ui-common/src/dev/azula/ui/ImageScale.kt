package dev.azula.ui

/** A re-encoded image: its bytes and the mime type they're encoded as. */
data class ScaledImage(val bytes: ByteArray, val mime: String)

/**
 * Decode [bytes], downscale so the longest side is at most [maxDim] (never
 * upscaling), and re-encode — keeping avatar frames small enough to ride one
 * newline-delimited JSON line. Returns null on any failure. Actuals live in
 * src@android (Bitmap), src@jvm and src@ios (skia).
 */
expect fun scaleImageForAvatar(bytes: ByteArray, maxDim: Int = 256): ScaledImage?
