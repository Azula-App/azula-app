package dev.azula.vmhost

import java.io.File
import java.io.FileOutputStream

/**
 * The persisted [HostState]: one small JSON file, replaced atomically (write a
 * sibling temp file, then rename), so a reader in the other process never sees a
 * torn write. Pure `java.io`: the observer lives in `HostStateObserver.kt`.
 */
internal class HostStateStore(val dir: File) {
    val file = File(dir, FILE_NAME)

    @Synchronized fun write(state: HostState) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use {
            it.write(HostStateCodec.encode(state).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("rename failed for ${file.name}")
    }

    /** The stored state; a missing or unreadable file reads as STOPPED. */
    fun read(): HostState = try {
        HostStateCodec.decode(file.readText(Charsets.UTF_8)) ?: HostState()
    } catch (_: Exception) {
        HostState()
    }

    companion object {
        const val FILE_NAME = "host-state.json"
    }
}

/**
 * The state the UI should show: a state that claims to be active but whose writer
 * process is gone (LMK, crash, force-stop: nothing got to write STOPPED) is shown
 * as an error, not as a host that's still running.
 */
internal fun effectiveState(stored: HostState, isProcessAlive: (Int) -> Boolean): HostState =
    if (stored.active && !isProcessAlive(stored.pid)) {
        stored.copy(phase = HostPhase.ERROR, detail = "stopped — the host process was killed")
    } else {
        stored
    }
