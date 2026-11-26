package app.azula.vmhost.ftl

/** One command as it ended. [hung]: it was still running at its limit, and was killed. */
data class Ran(
    val what: String,
    val code: Int,
    val stdout: String,
    val stderr: String = "",
    val seconds: Double = 0.0,
    val hung: Boolean = false,
) {
    /** stdout as `$(…)` would give it. */
    val captured: String get() = stdout.trimEnd('\n')
}

/**
 * What `azula-cli/vm/e2e/cloud.sh` asserts, step by step, on what the commands gave. Nothing here is
 * laxer than the script. Each check throws with the reason, [TimedOutException] for a command that
 * was killed at its limit. Pure, so every one is unit-tested.
 */
object CloudChecks {
    /** [covers]: the rows of the remote-compute scenario catalog cloud.sh gives it. */
    data class Spec(val covers: String, val maxSeconds: Double)

    /**
     * Every step, in order, and how long it may take. Two send enough through the relay for it to
     * throttle them, down to about 40 KB/s: the 2 MB of `reattach` then take 1 min, and the 9 MB each
     * way of `transfer` 7 to 8.
     */
    val STEPS = linkedMapOf(
        "start" to Spec("H1", 300.0),
        "identity" to Spec("", 90.0),
        "streams" to Spec("H2", 90.0),
        "reattach" to Spec("S4", 300.0),
        "transfer" to Spec("S7 H4", 720.0),
        // cloud.sh's 15 min for a guest that has to install cc first, and the rest.
        "tree" to Spec("H4", 1200.0),
        "detach" to Spec("H3", 180.0),
        "survivor" to Spec("S9 H8", 180.0),
        "timeout" to Spec("", 90.0),
    )

    const val SEQ_LAST = 300_000
    const val BLOB_BYTES = 9_000_000L
    const val TIMEOUT_LIMIT_S = 20.0

    private fun inTime(r: Ran) {
        if (r.hung) throw TimedOutException("${r.what}: still running after ${"%.0f".format(r.seconds)} s, so it was killed${said(r)}")
    }

    fun exited(r: Ran, want: Int = 0) {
        inTime(r)
        check(r.code == want) { "${r.what}: exit ${r.code}, want $want${said(r)}" }
    }

    fun identity(r: Ran) {
        exited(r)
        check(r.captured == "Linux aarch64") { "expected 'Linux aarch64', got '${r.captured}'" }
    }

    fun streams(r: Ran) {
        exited(r, 7)
        check("to-stdout" in r.stdout.lines()) { "stdout has no line 'to-stdout': '${r.captured}'" }
        check("to-stderr" in r.stderr) { "stderr has no 'to-stderr': '${r.stderr.trim()}'" }
        check("to-stderr" !in r.stdout) { "stderr leaked into stdout" }
    }

    /** [got] is null when the output couldn't be read back. */
    fun reattach(r: Ran, got: Digest?, want: Digest) {
        exited(r)
        check(Notice.reattached(r.stderr)) { "the fault never fired (no reattach notice)${said(r)}" }
        same("output after reattach", got, want)
    }

    fun transferred(r: Ran, verb: String) {
        exited(r)
        check(Notice.resumed(r.stderr, verb)) { "$verb: the fault never fired (no resume notice)${said(r)}" }
    }

    /** [remote] is `sha256sum` of the guest's copy. */
    fun roundTrip(blob: Digest?, back: Digest?, remote: Ran) {
        check(blob != null && blob.bytes == BLOB_BYTES) { "the blob is ${blob?.bytes ?: "unreadable"}, not $BLOB_BYTES bytes" }
        same("round trip", back, blob)
        exited(remote)
        val guest = Digest.fromSha256sum(remote.stdout)
        checkNotNull(guest) { "sha256sum in the guest said '${remote.captured}'" }
        check(guest == blob.sha256) { "guest copy differs: $guest, not ${blob.sha256}" }
    }

    /** Built, and run: what `./hello` printed is the proof of both. */
    fun built(r: Ran) {
        exited(r)
        check(r.stdout.lines().any { HELLO.matches(it) }) { "./hello said '${r.captured}'" }
    }

    fun binary(head: ByteArray?) {
        checkNotNull(head) { "couldn't read the pulled binary" }
        Elf.notAarch64(head)?.let { error("artifact isn't an aarch64 ELF: $it") }
    }

    /** The job's ref, from `exec --detach`. */
    fun detached(r: Ran): String {
        exited(r)
        checkNotNull(JobRef.id(r.captured)) { "exec --detach printed '${r.captured}', not a job" }
        return r.captured
    }

    fun listed(jobs: Ran, ref: String) {
        exited(jobs)
        check(JobRef.id(ref)!! in jobs.stdout) { "jobs doesn't list $ref" }
    }

    fun attachedToDetached(r: Ran) {
        exited(r, 5)
        check(r.captured == "tick1\ntick2\ntick3") { "attach output: '${r.captured}'" }
    }

    fun lastLog(r: Ran) {
        exited(r)
        check(r.captured == "tick3") { "logs --tail 1 said '${r.captured}'" }
    }

    /**
     * The client was killed while its job ran: it had started the job, and SIGKILL is what ended it
     * ([r]'s code is its wait status). A client that had ended by itself would not have died of it.
     */
    fun killedMidJob(r: Ran) {
        inTime(r)
        check("started" in r.stderr) { "the client never started its job${said(r)}" }
        check(r.code == 128 + 9) { "the client ended with ${r.code} before it could be killed (want 137, SIGKILL)${said(r)}" }
    }

    /** The job's ref, from `jobs --json`. */
    fun survivorRef(jobs: Ran): String {
        exited(jobs)
        val ref = try {
            Jobs.refByLabel(jobs.stdout, SURVIVOR)
        } catch (e: IllegalArgumentException) {
            error("jobs --json isn't what it should be: ${e.message}")
        }
        return checkNotNull(ref) { "no job labelled $SURVIVOR in jobs --json" }
    }

    fun survived(r: Ran) {
        exited(r)
        check("survived" in r.stdout.lines()) { "survivor: output '${r.captured}'" }
    }

    /** [Ran.hung] matters here: the wrapper that kills a hung client reports 124 too. */
    fun timedOut(r: Ran) {
        exited(r, 124)
        check(r.seconds < TIMEOUT_LIMIT_S) { "timeout took ${"%.1f".format(r.seconds)} s, want under ${TIMEOUT_LIMIT_S.toInt()}" }
    }

    /** Null is a file that couldn't be read, which is never the same as anything. */
    fun same(what: String, got: Digest?, want: Digest?) {
        check(got != null && want != null) { "$what: couldn't be read" }
        check(got == want) { "$what differs: ${got.bytes} bytes ${got.sha256}, not ${want.bytes} bytes ${want.sha256}" }
    }

    const val SURVIVOR = "e2e-survivor"
    private val HELLO = Regex("""hello from \S+ on aarch64""")

    /** The end of what a command printed, on one line, for a failure's message. */
    private fun said(r: Ran): String = listOf(Notice.withoutLog(r.stderr), r.stdout).firstOrNull { it.isNotBlank() }
        ?.let { text -> ": " + text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ") { it.trim() }.takeLast(300) }.orEmpty()
}
