package app.azula.vmhost.ftl

import android.os.SystemClock

/**
 * The steps of `azula-cli/vm/e2e/cloud.sh`, run from the phone's shell: the same azula commands, with
 * what they gave judged by [CloudChecks]. The phone has toybox and mksh, so hashing, the ELF check
 * and JSON are done here, and the shell only runs azula and moves bytes.
 *
 * [client] runs a script with azula's environment (token, relay-only) for at most the given seconds
 * plus some slack. [azula] is the binary, [dir] a directory of ours. [hostThrottled] counts the
 * throttle warnings in the host's log so far.
 */
class CloudSteps(
    private val shell: Shell,
    private val steps: Steps,
    private val azula: String,
    private val dir: String,
    private val hostThrottled: () -> Int,
    private val client: (script: String, seconds: Long) -> Shell.Result,
) {
    private val log = StringBuilder()
    private var clientThrottled = false

    /** Every command, how it ended and what it said. Not scrubbed: [Report.write] does that. */
    fun transcript(): String = log.toString()

    fun runAll() {
        val ready = shell.run("rm -rf $dir && mkdir -p $dir/src/lib $dir/out")
        check(ready.ok) { "couldn't make $dir: ${ready.all}" }

        step("start") {
            CloudChecks.exited(run("doctor", 90))
            CloudChecks.exited(run("vm start $VM", 220, verb = ""))
            CloudChecks.exited(run("doctor --vm $VM", 120))
            "doctor, vm start, doctor --vm: every stage, including the guest worker"
        }

        step("identity") {
            val r = run("exec -q -- uname -sm", 60)
            CloudChecks.identity(r)
            "runs in the arm64 Linux guest (${r.captured})"
        }

        step("streams") {
            CloudChecks.streams(run("exec -q -- sh -c 'echo to-stdout; echo to-stderr >&2; exit 7'", 60))
            "exit 7, stdout and stderr separate"
        }

        step("reattach") {
            val want = Digest.ofSeq(CloudChecks.SEQ_LAST)
            val r = run("exec -q -- seq 1 ${CloudChecks.SEQ_LAST}", CAP, env = "AZULA_COMPUTE_FAULT=drop-after:400000", out = "got")
            val got = shell.digest("$dir/got")
            CloudChecks.reattach(r, got, want)
            "${want.bytes} bytes, identical after a forced drop"
        }

        step("transfer") {
            val made = shell.run("head -c ${CloudChecks.BLOB_BYTES} /dev/urandom >$dir/blob", 60_000)
            check(made.ok) { "couldn't make the blob: ${made.all}" }
            val fault = "AZULA_COMPUTE_FAULT=drop-after:3000000"
            // Throttling alone mustn't fail it: both may use all of the step's time, less what went before.
            val push = run("push $dir/blob e2e/blob.bin", CAP, env = fault)
            CloudChecks.transferred(push, "push")
            val pull = run("pull e2e/blob.bin $dir/blob.back", CAP, env = fault)
            CloudChecks.transferred(pull, "pull")
            val remote = run("exec -q -- sha256sum e2e/blob.bin", 60)
            CloudChecks.roundTrip(shell.digest("$dir/blob"), shell.digest("$dir/blob.back"), remote)
            "9 MB pushed in ${"%.0f".format(push.seconds)} s and pulled in ${"%.0f".format(pull.seconds)} s through two forced drops, sha256 identical on all three copies"
        }

        step("tree") {
            shell.write("$dir/src/hello.c", HELLO_C.toByteArray())
            shell.write("$dir/src/lib/msg.h", "#define MSG \"hello\"\n".toByteArray())
            CloudChecks.exited(run("push -r $dir/src e2e/src", 120))
            // The time limit is cloud.sh's: a guest without cc installs it first.
            val built = run("exec -q --timeout 15m -- sh -c '$BUILD'", 930)
            CloudChecks.built(built)
            CloudChecks.exited(run("pull e2e/src/hello $dir/out/hello", 120))
            CloudChecks.binary(shell.head("$dir/out/hello", 64))
            CloudChecks.exited(run("pull -r e2e/src $dir/tree", 120))
            val whole = shell.run("[ -f $dir/tree/lib/msg.h ] && [ -f $dir/tree/hello ]")
            check(whole.ok) { "pulled tree incomplete: ${shell.run("ls -R $dir/tree").all}" }
            CloudChecks.same("hello in the pulled tree", shell.digest("$dir/tree/hello"), shell.digest("$dir/out/hello"))
            CloudChecks.same("msg.h in the pulled tree", shell.digest("$dir/tree/lib/msg.h"), shell.digest("$dir/src/lib/msg.h"))
            "tree in, aarch64 binary and tree out; '${built.captured.lines().last()}' after ${"%.1f".format(built.seconds)} s of build"
        }

        step("detach") {
            val ref = CloudChecks.detached(
                run("exec --detach --label e2e-detach -- sh -c 'for i in 1 2 3; do echo tick\$i; sleep 1; done; exit 5'", 60),
            )
            CloudChecks.listed(run("jobs", 60), ref)
            CloudChecks.attachedToDetached(run("attach $ref", 60))
            CloudChecks.lastLog(run("logs --tail 1 $ref", 60))
            "$ref replayed in full, exit 5 via attach"
        }

        step("survivor") {
            CloudChecks.killedMidJob(killedClient())
            val ref = CloudChecks.survivorRef(run("jobs --json", 60))
            CloudChecks.survived(run("attach $ref", 60))
            "$ref finished after its client was SIGKILLed"
        }

        step("timeout") {
            val r = run("exec -q --timeout 3s -- sleep 60", 60)
            CloudChecks.timedOut(r)
            "timeout, exit 124 in ${"%.1f".format(r.seconds)} s"
        }
    }

    private fun step(name: String, body: () -> String) {
        log.append("== $name\n")
        val spec = CloudChecks.STEPS.getValue(name)
        clientThrottled = false
        val before = hostThrottled()
        steps.run(
            name, spec.covers, spec.maxSeconds,
            throttledBy = { listOfNotNull("client".takeIf { clientThrottled }, "host".takeIf { hostThrottled() > before }) },
            body = body,
        )
        val s = steps.all().last()
        log.append("${s.outcome}${if (s.throttledBy.isEmpty()) "" else " (the relay throttled: ${s.throttledBy.joinToString()})"}: ${s.detail}\n\n")
    }

    /**
     * `azula compute [args]` (or `azula [args]` with an empty [verb]), killed after [cap] seconds or at
     * the end of the step's or the run's time. stdout goes to the file [out] in [dir] if there is one.
     */
    private fun run(args: String, cap: Long, env: String = "", out: String? = null, verb: String = "compute "): Ran {
        val s = steps.limit("$verb$args", cap)
        return script(
            "$verb$args",
            "$env timeout -k 5 $s $azula $verb$args${if (out == null) "" else " >$out"}".trim(),
            s,
        )
    }

    /**
     * cloud.sh's killed client: its job sleeps 6 s, and the client is SIGKILLed in the middle of it.
     * cloud.sh waits 3 s before the kill and hopes the job has started; this waits for azula to say
     * so (hence no -q), then 2 s more. The client runs without a wrapper, so what dies is azula
     * itself, and its wait status is the script's.
     */
    private fun killedClient(): Ran = script(
        "compute exec --label ${CloudChecks.SURVIVOR} -- … (killed)",
        """
        $azula compute exec --label ${CloudChecks.SURVIVOR} -- sh -c 'sleep 6; echo survived' >kill.out 2>&1 &
        pid=${'$'}!
        i=0
        while [ ${'$'}i -lt 150 ] && ! grep -q started kill.out && kill -0 ${'$'}pid 2>/dev/null; do sleep 0.2; i=${'$'}((i+1)); done
        sleep 2
        kill -KILL ${'$'}pid 2>/dev/null
        wait ${'$'}pid 2>/dev/null
        rc=${'$'}?
        cat kill.out >&2
        exit ${'$'}rc
        """.trimIndent(),
        steps.limit("the killed client", 60),
    )

    private fun script(what: String, script: String, seconds: Long): Ran {
        val t = SystemClock.elapsedRealtime()
        val r = client("export AZULA_COMPUTE_VM=$VM\ncd $dir || exit 99\n$script", seconds)
        val took = (SystemClock.elapsedRealtime() - t) / 1000.0
        val ran = Ran(what, r.code, r.stdout, r.stderr, took, hung = r.timedOut || took >= seconds)
        val throttled = Notice.throttled(r.stderr)
        if (throttled) clientThrottled = true
        log.append("$ azula $what\n[exit ${ran.code} in ${"%.1f".format(took)} s")
            .append(if (ran.hung) ", killed at its limit" else "").append(if (throttled) ", throttled by the relay" else "").append("]\n")
        for (text in listOf(r.stdout, r.stderr)) if (text.isNotBlank()) log.append(text.trimEnd().takeLast(4_000)).append('\n')
        return ran
    }

    private companion object {
        /** For a command that is only bound by its step. */
        const val CAP = 3_600L

        /** The one VM of a phone's broker, whatever it is hosted on (android-host-jni). */
        const val VM = "default"

        // As in cloud.sh, quoted for a single-quoted sh argument.
        const val BUILD = "command -v cc >/dev/null || { sudo apt-get update -qq && " +
            "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential >/dev/null; }; " +
            "cd e2e/src && cc -O2 -o hello hello.c && ./hello"

        const val HELLO_C = "#include <stdio.h>\n" +
            "#include <sys/utsname.h>\n" +
            "#include \"lib/msg.h\"\n" +
            "int main(void) { struct utsname u; uname(&u); " +
            "printf(\"%s from %s on %s\\n\", MSG, u.nodename, u.machine); return 0; }\n"
    }
}
