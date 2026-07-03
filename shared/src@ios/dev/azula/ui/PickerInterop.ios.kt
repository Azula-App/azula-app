@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.azula.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.posix.memcpy

/**
 * Shared iOS presentation plumbing for [rememberFilePicker] and `rememberMediaPicker`
 * (MediaPicker.ios.kt) — the repo's first UIKit interop. Both PHPickerViewController
 * (image/video) and UIDocumentPickerViewController (audio/generic file) hand back
 * only a *temporary* file URL that's valid inside their completion callback and
 * gone once it returns, per Apple's documented lifetime for these URLs — every
 * path here reads the bytes out synchronously before returning from that callback.
 *
 * Presenting functions return the delegate object they created: callers must hold
 * a strong reference to it (in a `remember`-captured `var`) until [onPicked]/the
 * result callback fires, since nothing else in ARC keeps it alive once this
 * function returns.
 */

/** Walks up the presented-view-controller chain to find the frontmost VC to present from. */
internal fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    // keyWindow is deprecated and returns nil on scene-based apps (verified on the
    // iOS 26 simulator) -- prefer walking the connected scenes' windows, falling
    // back to keyWindow for any pre-scene configuration.
    val sceneWindows = app.connectedScenes
        .mapNotNull { it as? UIWindowScene }
        .flatMap { scene -> scene.windows.mapNotNull { w -> w as? UIWindow } }
    val window = sceneWindows.firstOrNull { it.keyWindow }
        ?: sceneWindows.firstOrNull()
        ?: app.keyWindow
    var top = window?.rootViewController ?: return null
    while (true) {
        val presented = top.presentedViewController ?: return top
        top = presented
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    val out = ByteArray(len)
    if (len > 0) {
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return out
}

/**
 * Reads the full contents of a file [url] as an [NSData], or null on failure.
 * Deliberately goes through [NSFileManager] rather than `NSData(contentsOfURL:)` —
 * the latter's ObjC factory/initializer pair collide under Kotlin/Native's
 * interop rules for nullable-returning factories, so the plain generated
 * companion function doesn't exist. NSFileManager.contentsAtPath has no such
 * ambiguity.
 */
private fun readFile(url: NSURL): NSData? = url.path?.let { NSFileManager.defaultManager.contentsAtPath(it) }

/**
 * Well-known system UTIs used by pickers. UTType's Swift convenience static
 * properties (`.data`, `.audio`, `.item`, …) are Swift-only extensions that
 * don't appear in the Objective-C header cinterop parses, so they're
 * unavailable here — go through the stable reverse-DNS identifier instead
 * (both force-unwrapped since these are guaranteed-present system types).
 */
internal val UTTypeDataKind: UTType = UTType.typeWithIdentifier("public.data")!!
internal val UTTypeAudioKind: UTType = UTType.typeWithIdentifier("public.audio")!!

/**
 * Presents a PHPickerViewController filtered to images or videos. Returns the
 * delegate the caller must keep alive until [onResult] fires (or null if there's
 * no view controller to present from).
 */
/**
 * Pickers are launched from a Compose DropdownMenu item, and Compose hosts
 * popups in a transient presented view controller (ComposeLayersViewController)
 * that is torn down as the menu closes -- presenting from it (it's the frontmost
 * VC at tap time) gets the picker dismissed along with it. Defer past the popup
 * teardown and resolve the presenter fresh at present time.
 */
private fun presentAfterPopupTeardown(present: () -> Unit) {
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, 400L * 1_000_000L), // 400 ms
        dispatch_get_main_queue(),
    ) { present() }
}

internal fun presentPhPicker(imagesOnly: Boolean, onResult: (PickedFile) -> Unit): NSObject? {
    val config = PHPickerConfiguration()
    config.filter = if (imagesOnly) PHPickerFilter.imagesFilter() else PHPickerFilter.videosFilter()
    config.selectionLimit = 1L
    val picker = PHPickerViewController(configuration = config)
    val delegate = PhPickerDelegate(onResult)
    picker.delegate = delegate
    presentAfterPopupTeardown {
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
    return delegate
}

/**
 * Presents a UIDocumentPickerViewController scoped to [contentTypes]. Returns the
 * delegate the caller must keep alive until [onResult] fires (or null if there's
 * no view controller to present from).
 */
internal fun presentDocumentPicker(contentTypes: List<UTType>, onResult: (PickedFile) -> Unit): NSObject? {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes)
    picker.allowsMultipleSelection = false
    val delegate = DocumentPickerDelegate(onResult)
    picker.delegate = delegate
    presentAfterPopupTeardown {
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
    return delegate
}

private class PhPickerDelegate(
    private val onResult: (PickedFile) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        // Unlike UIImagePickerController, PHPickerViewController does NOT auto-dismiss —
        // the delegate is responsible for it.
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        val provider = result.itemProvider
        val typeId = provider.registeredTypeIdentifiers.firstOrNull() as? String ?: return
        provider.loadFileRepresentationForTypeIdentifier(typeId) { url, _ ->
            // `url` is only valid synchronously inside this callback — copy the bytes now.
            val bytes = url?.let { readFile(it) }?.toByteArray()
            if (bytes != null) {
                val name = provider.suggestedName ?: url.lastPathComponent ?: "media"
                val mime = UTType.typeWithIdentifier(typeId)?.preferredMIMEType ?: "application/octet-stream"
                dispatch_async(dispatch_get_main_queue()) {
                    onResult(PickedFile(name = name, mime = mime, bytes = bytes))
                }
            }
        }
    }
}

private class DocumentPickerDelegate(
    private val onResult: (PickedFile) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val accessed = url.startAccessingSecurityScopedResource()
        try {
            // Read synchronously here too — the picker may revoke access to the URL
            // once this delegate call returns.
            val bytes = readFile(url)?.toByteArray() ?: return
            val name = url.lastPathComponent ?: "file"
            val ext = url.pathExtension
            val mime = (if (ext != null) UTType.typeWithFilenameExtension(ext) else null)
                ?.preferredMIMEType ?: "application/octet-stream"
            dispatch_async(dispatch_get_main_queue()) {
                onResult(PickedFile(name = name, mime = mime, bytes = bytes))
            }
        } finally {
            if (accessed) url.stopAccessingSecurityScopedResource()
        }
    }
}
