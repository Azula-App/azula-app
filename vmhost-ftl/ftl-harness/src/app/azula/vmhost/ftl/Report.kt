package app.azula.vmhost.ftl

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * What the run leaves behind: files under [dir] (Test Lab pulls the directory), logcat lines under
 * [TAG], and result.json. Every string goes through [scrub] on the way out, and [write] refuses
 * one that still holds a token.
 */
class Report(private val shell: Shell, val scrub: Scrub, val dir: String) {
    private val t0 = SystemClock.elapsedRealtime()
    private val timeline = StringBuilder("elapsed_s\tphase\tstep\tbytes\tdetail\n")
    val result = JSONObject()

    fun elapsedSeconds(): Double = (SystemClock.elapsedRealtime() - t0) / 1000.0

    fun open() {
        shell.run("mkdir -p $dir")
        log("output directory: $dir")
    }

    fun log(message: String) {
        for (line in scrub.clean(message).lines()) Log.i(TAG, line)
    }

    /** One line of the status-text timeline. */
    fun mark(s: HostStatus) {
        val bytes = if (s.total > 0) "${s.done}/${s.total}" else ""
        val row = listOf("%.1f".format(elapsedSeconds()), s.phase, s.step.orEmpty(), bytes, s.detail.replace('\n', ' '))
        timeline.append(scrub.clean(row.joinToString("\t"))).append('\n')
        log("host: ${s.key}${if (bytes.isEmpty()) "" else " $bytes"}")
    }

    fun write(name: String, text: String) {
        val clean = scrub.clean(text)
        check(!scrub.leaks(clean)) { "$name would hold a token; not written" }
        shell.write("$dir/$name", clean.toByteArray())
    }

    fun screenshot(name: String) {
        shell.run("screencap -p $dir/$name.png")
    }

    fun finish() {
        write("timeline.tsv", timeline.toString())
        write("result.json", result.toString(2) + "\n")
    }

    companion object {
        const val TAG = "vmhost-ftl"
    }
}
