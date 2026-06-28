package dev.azula.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode a raw image byte array into a Compose [ImageBitmap].
 * Returns null on any failure (unrecognised format, OOM, etc.).
 * Actuals live in src@android, src@jvm, and src@ios.
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
