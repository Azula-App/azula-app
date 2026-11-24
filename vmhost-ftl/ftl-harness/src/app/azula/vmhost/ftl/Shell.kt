package app.azula.vmhost.ftl

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Runs scripts as the shell user, through [UiAutomation.executeShellCommandRwe] (the same uid and
 * SELinux domain as `adb shell`, which is what may grant development permissions and `run-as` a
 * debuggable app).
 *
 * The command is always a bare `sh`, and the script goes in on its stdin, for two reasons:
 * executeShellCommand splits its argument on spaces with no quoting, and a token in a script never
 * appears in any process's argv. Nothing here logs a script or its output.
 */
class Shell(private val automation: UiAutomation) {
    class Result(val code: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
        val ok get() = code == 0 && !timedOut

        /** stdout then stderr, for messages. */
        val all get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    /** Runs [script]. Its commands get an empty stdin, so none of them can eat the rest of the script. */
    fun run(script: String, timeoutMs: Long = 30_000): Result {
        val fds = automation.executeShellCommandRwe("/system/bin/sh")
        val (outFd, inFd, errFd) = fds
        var out = ""
        var err = ""
        val readers = listOf(
            thread(name = "shell-out") { out = drain(outFd) },
            thread(name = "shell-err") { err = drain(errFd) },
        )
        runCatching { send(inFd, "(\n$script\n) </dev/null\nrc=$?\necho\necho \"$RC\$rc\"\n".toByteArray()) }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        for (t in readers) t.join((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        val timedOut = readers.any { it.isAlive }
        if (timedOut) {
            runCatching { outFd.close() }
            runCatching { errFd.close() }
            readers.forEach { it.join(1_000) }
        }
        val i = out.lastIndexOf(RC)
        val code = if (i < 0) -1 else out.substring(i + RC.length).trim().toIntOrNull() ?: -1
        // The echo before the marker keeps it on its own line when the output has no final newline.
        return Result(code, if (i < 0) out else out.substring(0, i).removeSuffix("\n"), err, timedOut)
    }

    /** Writes [bytes] to [path], which must have no spaces, as the shell user. */
    fun write(path: String, bytes: ByteArray) {
        require(path.none { it.isWhitespace() }) { "no spaces in $path" }
        val (outFd, inFd, errFd) = automation.executeShellCommandRwe("dd of=$path bs=65536 status=none")
        runCatching { send(inFd, bytes) }
        drain(outFd)
        drain(errFd)
    }

    private fun send(fd: ParcelFileDescriptor, bytes: ByteArray) =
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { it.write(bytes) }

    private fun drain(fd: ParcelFileDescriptor): String =
        runCatching { ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrDefault("")

    private companion object {
        const val RC = "@@vmhost-rc="
    }
}
