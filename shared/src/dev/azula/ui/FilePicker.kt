package dev.azula.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** A file chosen by the user via the platform's native file picker. */
data class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Test seam for [rememberFilePicker]. The platform pickers are native modals
 * (`java.awt.FileDialog` on jvm, `UIDocumentPickerViewController` on ios, the
 * activity-result contract on android) -- a Compose UI test that clicked "pick
 * picture" would block on a real OS dialog forever, which is why the avatar
 * upload path had no coverage. Providing a [FilePicker] here swaps the picker
 * out for one the test drives directly; production code provides nothing and
 * gets the platform picker unchanged.
 */
fun interface FilePicker {
    /** Invoked in place of opening the platform picker. Call [onPicked] to
     *  simulate a successful pick, or return without calling it to simulate the
     *  user cancelling. */
    fun pick(onPicked: (PickedFile) -> Unit)
}

/** The active [FilePicker] override, or null to use the platform picker. */
val LocalFilePicker = staticCompositionLocalOf<FilePicker?> { null }

/**
 * Returns a lambda that, when invoked, opens the platform file picker.
 * When the user picks a file, [onPicked] is called with the result.
 *
 * Honors [LocalFilePicker] when one is provided (tests); otherwise delegates to
 * [rememberPlatformFilePicker].
 */
@Composable
fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val override = LocalFilePicker.current
    // The platform picker is remembered unconditionally rather than inside the
    // null branch: `remember` calls must not be made conditionally, and the
    // override is a static composition local so this never actually re-runs.
    val platform = rememberPlatformFilePicker(onPicked)
    return if (override == null) platform else remember(override) { { override.pick(onPicked) } }
}

/**
 * Opens the real platform file picker. Actuals live in src@android, src@jvm,
 * and src@ios. Prefer [rememberFilePicker] at call sites -- it adds the
 * [LocalFilePicker] test seam on top of this.
 */
@Composable
expect fun rememberPlatformFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
