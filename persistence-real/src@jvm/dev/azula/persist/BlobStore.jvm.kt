package dev.azula.persist

import java.io.File

actual fun platformBlobStore(): BlobStore? = FileBlobStore(blobStoreDir())

/** `$AZULA_DATA_DIR/.azula` when the isolation override is set (see
 *  [azulaHomeDir]), else `~/.azula` as before. */
internal fun blobStoreDir(env: (String) -> String? = System::getenv) = File(azulaHomeDir(env), ".azula")
