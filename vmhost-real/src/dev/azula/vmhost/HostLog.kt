package dev.azula.vmhost

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends timestamped lines to `<filesDir>/logs/<name>.log` and mirrors them to logcat. */
internal object HostLog {
    @Volatile var dir: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized fun to(name: String, msg: String) {
        android.util.Log.i("vmhost", "$name: $msg")
        val d = dir ?: return
        try {
            FileWriter(File(d, "$name.log"), true).use { it.write("${fmt.format(Date())} $msg\n") }
        } catch (_: Exception) {
        }
    }
}
