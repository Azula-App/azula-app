package dev.azula.vmhost

import android.os.FileObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val RECHECK_MS = 3_000L

/**
 * Emits [effectiveState] now and on every change. A [FileObserver] on the state
 * directory (inotify) catches the writer's atomic rename; a slow recheck catches
 * the one change no file event announces, the `:vmhost` process dying.
 */
@Suppress("DEPRECATION") // FileObserver(File, Int) needs API 29; this lib's minSdk is 26.
internal fun HostStateStore.observe(isProcessAlive: (Int) -> Boolean): Flow<HostState> = callbackFlow {
    dir.mkdirs()
    val emit = { trySend(effectiveState(read(), isProcessAlive)); Unit }
    val observer = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == null || path == HostStateStore.FILE_NAME) emit()
        }
    }
    observer.startWatching()
    emit()
    launch {
        while (true) {
            delay(RECHECK_MS)
            emit()
        }
    }
    awaitClose { observer.stopWatching() }
}.distinctUntilChanged()
