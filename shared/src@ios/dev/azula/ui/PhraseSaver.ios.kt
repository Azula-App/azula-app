package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

/**
 * iOS: the share sheet, which is as close as the platform gets. There is no
 * public API to add an item to the user's password manager programmatically --
 * AutoFill's "Save Password" prompt is driven by the system observing a real
 * password field being submitted, and `SecAddSharedWebCredential` has been
 * deprecated since iOS 14. Password managers that ship a share extension
 * (1Password, Bitwarden) appear in the sheet as targets.
 *
 * Presented via [topViewController] (PickerInterop.ios.kt), the same presenter
 * the file/media pickers use. Unlike those, this isn't launched from a
 * DropdownMenu, so it doesn't need the popup-teardown defer.
 */
@Composable
actual fun rememberPlatformPhraseSaver(): (String, (PhraseSaveResult) -> Unit) -> Unit = remember {
    { phrase, onResult ->
        val presenter = topViewController()
        if (presenter == null) {
            onResult(PhraseSaveResult.UNAVAILABLE)
        } else {
            val sheet = UIActivityViewController(activityItems = listOf(phrase), applicationActivities = null)
            sheet.completionWithItemsHandler = { _, completed, _, _ ->
                onResult(if (completed) PhraseSaveResult.SAVED else PhraseSaveResult.CANCELLED)
            }
            // iPad presents this as a popover and hard-crashes without an anchor.
            sheet.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(sheet, animated = true, completion = null)
        }
    }
}
