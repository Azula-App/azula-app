package dev.azula.persist

import android.content.Context

// Android needs a Context, so the default is null; AzulaApplication builds the
// AppGraph with an AndroidBlobStore(applicationContext) instead.
actual fun platformBlobStore(): BlobStore? = null

/** Stores each blob as a flat file under filesDir/blobs/<sanitized-id>. */
fun AndroidBlobStore(context: Context): BlobStore = FileBlobStore(context.filesDir)
