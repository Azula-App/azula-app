package dev.azula.persist

import java.io.File

actual fun platformBlobStore(): BlobStore? =
    FileBlobStore(File(System.getProperty("user.home") ?: ".", ".azula"))
