@file:OptIn(ExperimentalForeignApi::class)

package dev.azula.state

import dev.azula.ui.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * The App Group container the `AzulaShare` extension and this app both read/
 * write -- both bundle ids need provisioning profiles with the matching App
 * Group capability added (a signing/release prerequisite; see the note in
 * `ios-app/module.xcodeproj`'s entitlements files).
 */
private const val APP_GROUP_ID = "group.app.azula"

@Serializable
private data class ShareManifestEntry(val name: String, val mime: String, val file: String)

/**
 * Drains the iOS Share Extension's App Group inbox: reads
 * `<container>/Inbox/manifest.json` (a JSON array of `{name, mime, file}`,
 * written by `AzulaShare`'s `ShareViewController.swift`), loads each listed
 * file's bytes into a [PickedFile], hands them to [ShareBus] (whose handler --
 * registered in [AzulaState.init] -- calls [AzulaState.stageShare], which
 * [dev.azula.ui.SharePickerOverlay] then surfaces), and deletes the drained
 * files so a later call doesn't redeliver them.
 *
 * Called from Swift (`ios-app/src/iosApp.swift`) whenever the scene becomes
 * active. This is the only way a share actually reaches the running app: an
 * iOS share extension can't foreground its host app, so the flow is "share
 * from another app, then switch to Azula yourself" -- at which point this runs
 * and the picker appears. No-op (and cheap) when nothing is pending.
 */
fun drainShareInbox() {
    val fileManager = NSFileManager.defaultManager
    val containerUrl = fileManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID) ?: return
    val inboxUrl = containerUrl.URLByAppendingPathComponent("Inbox") ?: return
    val inboxPath = inboxUrl.path ?: return
    val manifestPath = "$inboxPath/manifest.json"
    if (!fileManager.fileExistsAtPath(manifestPath)) return

    val manifestText = runCatching {
        NSData.dataWithContentsOfFile(manifestPath)?.toByteArray()?.decodeToString()
    }.getOrNull()?.takeUnless { it.isEmpty() }
    val entries = manifestText?.let { text ->
        runCatching { Json.decodeFromString<List<ShareManifestEntry>>(text) }.getOrNull()
    }.orEmpty()

    val items: List<PickedFile> = entries.mapNotNull { entry ->
        val data = fileManager.contentsAtPath("$inboxPath/${entry.file}") ?: return@mapNotNull null
        PickedFile(name = entry.name, mime = entry.mime, bytes = data.toByteArray())
    }

    // Clear the whole inbox regardless of partial-read failures -- a half-read
    // share shouldn't be retried forever on every future scene-activation.
    entries.forEach { entry -> runCatching { fileManager.removeItemAtPath("$inboxPath/${entry.file}", error = null) } }
    runCatching { fileManager.removeItemAtPath(manifestPath, error = null) }

    ShareBus.deliver(items)
}

private fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}
