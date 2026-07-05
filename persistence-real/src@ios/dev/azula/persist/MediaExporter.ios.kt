@file:OptIn(ExperimentalForeignApi::class)

package dev.azula.persist

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHPhotoLibrary

/**
 * iOS media export: images/video are added to the Photos library (via
 * [PHAssetCreationRequest], needs `NSPhotoLibraryAddUsageDescription` — the first
 * save shows the add-only Photos prompt); everything else is copied into
 * `Documents/Azula/`, which is visible in Files.app because Info.plist sets
 * `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`. No
 * `UIDocumentPicker` — auto-save is prompt-free apart from the OS Photos grant.
 */
actual fun platformMediaExporter(): MediaExporter? = IosMediaExporter()

class IosMediaExporter : MediaExporter {
    private val fileManager = NSFileManager.defaultManager

    override suspend fun export(path: String, name: String, mime: String): String? {
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")
        return if (isImage || isVideo) saveToPhotos(path, isVideo) else copyToFiles(path, name.ifBlank { "file" })
    }

    /** Blocking add-to-Photos. Runs off the main thread (export is launched on a
     *  background coroutine), which [PHPhotoLibrary.performChangesAndWait] requires. */
    private fun saveToPhotos(path: String, isVideo: Boolean): String? = memScoped {
        val url = NSURL.fileURLWithPath(path)
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val ok = PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                if (isVideo) PHAssetCreationRequest.creationRequestForAssetFromVideoAtFileURL(url)
                else PHAssetCreationRequest.creationRequestForAssetFromImageAtFileURL(url)
                Unit
            },
            error = errorPtr.ptr,
        )
        if (ok) "Photos" else null
    }

    private fun copyToFiles(path: String, name: String): String? {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: NSTemporaryDirectory()
        val dir = "$docs/Azula"
        fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val dest = uniquePath(dir, name)
        val ok = fileManager.copyItemAtPath(path, toPath = dest, error = null)
        return if (ok) "Files/Azula/${dest.substringAfterLast('/')}" else null
    }

    /** Not-yet-existing path in [dir] for [name], inserting ` (n)` before the
     *  extension on collision (`a.pdf` -> `a (1).pdf`). */
    private fun uniquePath(dir: String, name: String): String {
        val candidate = "$dir/$name"
        if (!fileManager.fileExistsAtPath(candidate)) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val p = "$dir/$base ($n)$ext"
            if (!fileManager.fileExistsAtPath(p)) return p
            n++
        }
    }
}
