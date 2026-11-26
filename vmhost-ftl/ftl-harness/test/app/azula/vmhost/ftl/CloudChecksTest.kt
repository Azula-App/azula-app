package app.azula.vmhost.ftl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every assertion of cloud.sh: what it takes, and what it doesn't. */
class CloudChecksTest {
    private fun ran(code: Int, stdout: String = "", stderr: String = "", seconds: Double = 1.0, hung: Boolean = false) =
        Ran("compute exec", code, stdout, stderr, seconds, hung)

    private fun refused(because: String, check: () -> Unit) {
        val e = assertThrows(because, IllegalStateException::class.java) { check() }
        assertTrue("'${e.message}' doesn't say '$because'", because in e.message.orEmpty())
        assertFalse("a wrong answer isn't a timeout", e is TimedOutException)
    }

    /** Killed at its limit: not the same as a wrong answer, and never a pass. */
    private fun timesOut(check: () -> Unit) {
        val e = assertThrows(TimedOutException::class.java) { check() }
        assertTrue(e.message, "still running after" in e.message.orEmpty() && "so it was killed" in e.message.orEmpty())
    }

    @Test fun exitCodes() {
        CloudChecks.exited(ran(0))
        CloudChecks.exited(ran(7), 7)
        refused("exit 255, want 0: azula: boom") { CloudChecks.exited(ran(255, "out", "azula: boom\n")) }
        refused("exit 0, want 7") { CloudChecks.exited(ran(0), 7) }
        timesOut { CloudChecks.exited(ran(0, seconds = 60.0, hung = true)) }
        timesOut { CloudChecks.exited(ran(124, stderr = "100%s of it", seconds = 60.0, hung = true), 124) }
        // No exit code at all: the shell's output stopped before it.
        refused("exit -1") { CloudChecks.exited(ran(-1)) }
    }

    @Test fun identity() {
        CloudChecks.identity(ran(0, "Linux aarch64\n"))
        for (other in listOf("Linux armv8l\n", "Linux x86_64\n", "Darwin arm64\n", "Linux aarch64 extra\n", " Linux aarch64\n", "")) {
            refused("expected 'Linux aarch64'") { CloudChecks.identity(ran(0, other)) }
        }
        refused("exit 1") { CloudChecks.identity(ran(1, "Linux aarch64\n")) }
    }

    @Test fun exitCodeAndSeparateStreams() {
        CloudChecks.streams(ran(7, "to-stdout\n", "to-stderr\n"))
        refused("exit 0, want 7") { CloudChecks.streams(ran(0, "to-stdout\n", "to-stderr\n")) }
        refused("no line 'to-stdout'") { CloudChecks.streams(ran(7, "", "to-stderr\n")) }
        refused("no line 'to-stdout'") { CloudChecks.streams(ran(7, "xto-stdout\n", "to-stderr\n")) }
        refused("no 'to-stderr'") { CloudChecks.streams(ran(7, "to-stdout\n", "")) }
        refused("leaked") { CloudChecks.streams(ran(7, "to-stdout\nto-stderr\n", "to-stderr\n")) }
    }

    @Test fun reattach() {
        val want = Digest.ofSeq(50)
        val notice = "azula: connection lost, reattaching to default/j_1\n"
        CloudChecks.reattach(ran(0, stderr = notice), want, want)
        refused("the fault never fired") { CloudChecks.reattach(ran(0), want, want) }
        // Connected again, but before the job had started: nothing was reattached.
        refused("the fault never fired") { CloudChecks.reattach(ran(0, stderr = "azula: connection lost, reconnecting\n"), want, want) }
        refused("exit 255") { CloudChecks.reattach(ran(255, stderr = notice), want, want) }
        refused("output after reattach differs") { CloudChecks.reattach(ran(0, stderr = notice), Digest.ofSeq(49), want) }
        refused("output after reattach differs") { CloudChecks.reattach(ran(0, stderr = notice), want.copy(sha256 = "0".repeat(64)), want) }
        refused("couldn't be read") { CloudChecks.reattach(ran(0, stderr = notice), null, want) }
    }

    @Test fun transfersThatWereDropped() {
        CloudChecks.transferred(ran(0, stderr = "azula: connection lost, resuming push at 2.9 MiB\n"), "push")
        refused("push: the fault never fired") { CloudChecks.transferred(ran(0, stderr = "azula: pushed 8.6 MiB → e2e/blob.bin\n"), "push") }
        refused("pull: the fault never fired") { CloudChecks.transferred(ran(0, stderr = "azula: connection lost, resuming push at 1 B\n"), "pull") }
        refused("exit 1") { CloudChecks.transferred(ran(1, stderr = "azula: connection lost, resuming push at 2.9 MiB\n"), "push") }
        timesOut { CloudChecks.transferred(ran(124, stderr = "azula: connection lost, resuming push at 2.9 MiB\n", seconds = 720.0, hung = true), "push") }
    }

    @Test fun threeCopiesOfTheBlob() {
        val blob = Digest(CloudChecks.BLOB_BYTES, "a".repeat(64))
        fun sum(hash: String, code: Int = 0) = ran(code, "$hash  e2e/blob.bin\n")
        CloudChecks.roundTrip(blob, blob, sum(blob.sha256))
        refused("not 9000000 bytes") { CloudChecks.roundTrip(blob.copy(bytes = 0), blob, sum(blob.sha256)) }
        refused("unreadable") { CloudChecks.roundTrip(null, blob, sum(blob.sha256)) }
        refused("round trip: couldn't be read") { CloudChecks.roundTrip(blob, null, sum(blob.sha256)) }
        refused("round trip differs") { CloudChecks.roundTrip(blob, blob.copy(sha256 = "b".repeat(64)), sum(blob.sha256)) }
        refused("round trip differs") { CloudChecks.roundTrip(blob, blob.copy(bytes = 8_999_999), sum(blob.sha256)) }
        refused("guest copy differs") { CloudChecks.roundTrip(blob, blob, sum("b".repeat(64))) }
        refused("sha256sum in the guest said ''") { CloudChecks.roundTrip(blob, blob, ran(0)) }
        refused("exit 1") { CloudChecks.roundTrip(blob, blob, sum(blob.sha256, code = 1)) }
    }

    @Test fun builtInTheGuest() {
        CloudChecks.built(ran(0, "hello from azula-avf on aarch64\n"))
        refused("./hello said") { CloudChecks.built(ran(0, "")) }
        refused("./hello said") { CloudChecks.built(ran(0, "hello from azula-avf on x86_64\n")) }
        refused("exit 127") { CloudChecks.built(ran(127, stderr = "sh: 1: cc: not found\n")) }
        timesOut { CloudChecks.built(ran(124, seconds = 930.0, hung = true)) }
    }

    @Test fun pulledBinary() {
        CloudChecks.binary(ByteArray(64).also { "\u007fELF\u0002\u0001".toByteArray(Charsets.ISO_8859_1).copyInto(it); it[18] = 183.toByte() })
        refused("isn't an aarch64 ELF: not an ELF file") { CloudChecks.binary(ByteArray(64)) }
        refused("couldn't read the pulled binary") { CloudChecks.binary(null) }
    }

    @Test fun twoUnreadableFilesArentTheSame() {
        CloudChecks.same("x", Digest.ofSeq(2), Digest.ofSeq(2))
        refused("x: couldn't be read") { CloudChecks.same("x", null, null) }
        refused("x: couldn't be read") { CloudChecks.same("x", Digest.ofSeq(2), null) }
        refused("x differs") { CloudChecks.same("x", Digest.ofSeq(2), Digest.ofSeq(3)) }
    }

    @Test fun detachedJob() {
        assertEquals("default/j_0a", CloudChecks.detached(ran(0, "default/j_0a\n", "azula: job default/j_0a started\n")))
        refused("not a job") { CloudChecks.detached(ran(0, "")) }
        refused("not a job") { CloudChecks.detached(ran(0, "j_0a\n")) }
        refused("exit 125") { CloudChecks.detached(ran(125, "default/j_0a\n")) }

        CloudChecks.listed(ran(0, "JOB  STATE\ndefault/j_0a  running\n"), "default/j_0a")
        refused("doesn't list default/j_0a") { CloudChecks.listed(ran(0, "no jobs on default\n"), "default/j_0a") }
        refused("exit 255") { CloudChecks.listed(ran(255, "default/j_0a\n"), "default/j_0a") }

        CloudChecks.attachedToDetached(ran(5, "tick1\ntick2\ntick3\n"))
        refused("exit 0, want 5") { CloudChecks.attachedToDetached(ran(0, "tick1\ntick2\ntick3\n")) }
        refused("attach output") { CloudChecks.attachedToDetached(ran(5, "tick2\ntick3\n")) }
        refused("attach output") { CloudChecks.attachedToDetached(ran(5, "tick1\ntick2\ntick2\ntick3\n")) }

        CloudChecks.lastLog(ran(0, "tick3\n"))
        refused("logs --tail 1") { CloudChecks.lastLog(ran(0, "tick2\ntick3\n")) }
        refused("logs --tail 1") { CloudChecks.lastLog(ran(0, "")) }
    }

    @Test fun clientKilledMidJob() {
        val started = "azula: job default/j_1 started\n"
        CloudChecks.killedMidJob(ran(137, stderr = started))
        refused("never started its job: azula: timed out") { CloudChecks.killedMidJob(ran(137, stderr = "azula: timed out\n")) }
        refused("never started its job") { CloudChecks.killedMidJob(ran(137)) }
        // It had already ended, whatever way: the kill proved nothing.
        refused("ended with 0 before") { CloudChecks.killedMidJob(ran(0, stderr = started)) }
        refused("ended with 255 before") { CloudChecks.killedMidJob(ran(255, stderr = started)) }
        timesOut { CloudChecks.killedMidJob(ran(-1, stderr = started, hung = true)) }
    }

    @Test fun survivorIsFoundAndFinished() {
        val jobs = """[{"label":"e2e-survivor","ref":"default/j_9","state":"running"}]"""
        assertEquals("default/j_9", CloudChecks.survivorRef(ran(0, jobs)))
        refused("no job labelled") { CloudChecks.survivorRef(ran(0, "[]")) }
        refused("isn't what it should be") { CloudChecks.survivorRef(ran(0, "no jobs on default")) }
        refused("exit 255") { CloudChecks.survivorRef(ran(255, jobs)) }

        CloudChecks.survived(ran(0, "survived\n"))
        refused("exit 255") { CloudChecks.survived(ran(255, "survived\n")) }
        refused("survivor: output ''") { CloudChecks.survived(ran(0, "")) }
        refused("survivor: output") { CloudChecks.survived(ran(0, "not survived\n")) }
    }

    @Test fun timeout() {
        CloudChecks.timedOut(ran(124, stderr = "azula: job timed out after 3s\n", seconds = 5.2))
        CloudChecks.timedOut(ran(124, seconds = 19.9))
        refused("timeout took 20.0 s") { CloudChecks.timedOut(ran(124, seconds = 20.0)) }
        refused("exit 0, want 124") { CloudChecks.timedOut(ran(0, seconds = 5.0)) }
        refused("exit 255, want 124") { CloudChecks.timedOut(ran(255, seconds = 5.0)) }
        // The wrapper's own 124, from killing a client that never came back.
        timesOut { CloudChecks.timedOut(ran(124, seconds = 10.0, hung = true)) }
    }

    @Test fun aFailureSaysWhatAzulaSaidAndNotItsLog() {
        val log = "2026-11-24T15:37:46Z  WARN azula::endpoint: system TLS roots unavailable"
        refused("exit 255, want 0: azula: gone") { CloudChecks.exited(ran(255, "", "$log\nazula: gone\n")) }
        refused("exit 255, want 0: what it printed") { CloudChecks.exited(ran(255, "what it printed\n", "$log\n")) }
        // The end of it, on one line: it goes into the run's one-line reason.
        refused("exit 1, want 0: azula: pushed 7 MiB | azula: pushed 8 MiB | azula: gone") {
            CloudChecks.exited(ran(1, "", "azula: pushed 6 MiB\nazula: pushed 7 MiB\n\n$log\nazula: pushed 8 MiB\nazula: gone\n"))
        }
        val e = assertThrows(IllegalStateException::class.java) { CloudChecks.exited(ran(255, "", "$log\n")) }
        assertEquals("compute exec: exit 255, want 0", e.message)
    }

    @Test fun everyStepHasANameAndEnoughTime() {
        assertEquals(
            listOf("start", "identity", "streams", "reattach", "transfer", "tree", "detach", "survivor", "timeout"),
            CloudChecks.STEPS.keys.toList(),
        )
        // A throttled transfer took 8 min on a phone and 7 on the emulator.
        assertEquals(12 * 60.0, CloudChecks.STEPS.getValue("transfer").maxSeconds, 0.0)
    }
}
