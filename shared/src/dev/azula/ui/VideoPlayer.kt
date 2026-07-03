package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays a local video file at [path] inline. `path` is a platform-native
 * filesystem path (see `MediaService.blobPath` / `AzulaState.blobPath`) — never a
 * remote URL, since a video attachment's body always lives fully on disk (in the
 * BlobStore) before this is ever shown (only [dev.azula.model.TransferState.Complete]
 * attachments reach this composable; see `AttachmentView`).
 *
 * Actuals: Android backs this with a `VideoView` (zero new deps) inside
 * [androidx.compose.ui.viewinterop.AndroidView]; iOS with an
 * `AVPlayerViewController` inside `UIKitViewController` (the repo's second UIKit
 * interop, after the media pickers); JVM/desktop has no lightweight inline video
 * widget in Compose, so it opens the file in the system's default player via
 * `java.awt.Desktop` instead (a poster + button, not an inline player).
 */
@Composable
expect fun VideoPlayer(path: String, modifier: Modifier)
