package dev.azula.ui

/**
 * Write [bytes] (e.g. a decoded `data:audio/...;base64,` URI) to a platform
 * temp/cache file and return its path, or null on failure -- [rememberAudioPlayer]
 * needs a real file path, not raw bytes. [key] namespaces the filename (a stable
 * id, e.g. an attachment/component id) so repeat calls for the same logical audio
 * reuse the same file rather than accumulating garbage.
 */
expect fun cacheAudioFile(bytes: ByteArray, key: String): String?
