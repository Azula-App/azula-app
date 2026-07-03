package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

@Composable
actual fun VideoPlayer(path: String, modifier: Modifier) {
    UIKitViewController(
        modifier = modifier,
        factory = {
            AVPlayerViewController().apply {
                player = AVPlayer(playerItem = AVPlayerItem(uRL = NSURL.fileURLWithPath(path)))
            }
        },
    )
}
