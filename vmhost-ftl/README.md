# vmhost-ftl

One instrumentation run that proves the on-phone VM host on a physical device, for
Firebase Test Lab (task 5.3 of the OpenSpec change `compute-android-app-host`).
Nothing here ships.

| File in `out/` | Test Lab flag | What it is |
|---|---|---|
| `ftl-stub.apk` | `--app` | `app.azula.vmhost.ftl.stub`: no code of its own, only the package the test instruments |
| `ftl-harness.apk` | `--test` | `app.azula.vmhost.ftl.harness`: the instrumentation (`ftl-harness/`) |
| `vmhost-testapp.apk` | `--additional-apks` | `app.azula.vmhost.test`: the host app that is granted and driven |
| `azula` | `--other-files` | static arm64 `azula`, run from the device's shell |

Why three packages: `pm grant` of the VM permissions changes the gids of the package it
grants to, and Android then kills every process of that package. If that were the
instrumented package, the test would die at its first grant.

## Build

```sh
vmhost-ftl/build.sh
```

It builds the three APKs with `./kotlin build`, collects them and `azula` in `out/`
(gitignored), and checks what Test Lab and Android will: package names, one signing
certificate, the instrumentation target, that only the host app asks for the VM
permissions, that the stub has a dex (Test Lab rejects a code-less APK), and that the
host app carries `libazula_host.so` and `seed.iso`. It builds the host's native artifacts
(`scripts/vmhost-artifacts.sh`) and the static `azula`
(`../azula-cli/vm/android-host/build-broker.sh`) if they're missing. Both are used as
they are if present, so delete them after changing azula-cli.

Everything is signed with `~/.android/debug.keystore`, so builds from any checkout
install over each other. The script stops if that file is missing.

`deps.sha256` pins the bytes of every library in the harness. The build stops if the
resolved set or any hash differs. After a version bump: `build.sh --record-deps`, then
compare the changed lines with the Maven repositories' own checksums.

Kotlin Toolchain 0.14.1 has no product for Android instrumentation tests, but it builds a
test APK anyway: that's only an APK whose manifest has an `<instrumentation>` element and
which is signed like its target. So the harness is an `android/app` whose `src/` holds the
tests. `ftl-harness/test/` holds unit tests of its pure parts (`./check -m ftl-harness`).

## Run on Test Lab

From the azula-app root, after `build.sh`. It runs in gcloud's active project, which needs
the Cloud Testing and Cloud Tool Results APIs. Model and version pairs are from
`gcloud firebase test android models list`.

```sh
ftl() {  # ftl MODEL ANDROID_API
  gcloud firebase test android run \
    --type instrumentation \
    --app vmhost-ftl/out/ftl-stub.apk \
    --test vmhost-ftl/out/ftl-harness.apk \
    --additional-apks vmhost-ftl/out/vmhost-testapp.apk \
    --other-files /data/local/tmp/azula=vmhost-ftl/out/azula \
    --directories-to-pull /sdcard/Download/vmhost-ftl \
    --environment-variables requireHost=true,bootTimeoutMin=15 \
    --device model="$1",version="$2",locale=en,orientation=portrait \
    --timeout 40m \
    --num-flaky-test-attempts 0 \
    --no-auto-google-login \
    --no-performance-metrics \
    --results-history-name azula-vmhost \
    --client-details matrixLabel="vmhost-$1-$2"
}
ftl shiba 36   # Pixel 8,  Android 16
ftl tokay 36   # Pixel 9,  Android 16
ftl cubs  37   # Pixel 11, Android 17
```

- `--timeout 40m` is needed, and is gcloud's limit on the whole instrumentation. The default,
  15 min, is too short for a throttled run, and Test Lab's most on a physical device is 45
  min. (AndroidJUnitRunner's own `timeout_msec`, a limit per test case, isn't set. Leave it
  so: stage 5 is one test case.) See [How long it takes](#how-long-it-takes).
- Don't add `--use-orchestrator`. The stages share state and run in one process.
- Each run downloads the 300 MB Debian image over the device's network.

### Arguments (`--environment-variables`, or `-e` on `am instrument`)

| Name | Default | Meaning |
|---|---|---|
| `requireHost` | `false` | `true` turns "this device can't host" into a failure instead of a skip. Use it for devices that should host |
| `bootTimeoutMin` | `15` | how long Start may take to reach RUNNING |
| `runLimitMin` | `34` | minutes into the run when stage 5 is cut off: no step starts any more, and a running command is killed. Keep it 6 below `--timeout`, for the revoke and the report |
| `azulaPath` | `/data/local/tmp/azula` | where `--other-files` put the binary |
| `outDir` (or `additionalTestOutputDir`) | `/sdcard/Download/vmhost-ftl` | where the report goes; keep `--directories-to-pull` in step |

## What it does

Five test cases of `HostAcceptanceTest`, in order. A later one is skipped, with the reason,
when one it needs didn't pass.

1. `t1_azulaRunsFromTheShell`: copies the pushed binary, makes the copy executable (the push
   may drop the exec bit or leave another owner) and runs `azula --version` as the shell user.
2. `t2_grantsReachTheHostOnly`: `pm grant`s `MANAGE_VIRTUAL_MACHINE`,
   `USE_CUSTOM_VIRTUAL_MACHINE` and `POST_NOTIFICATIONS` to the host app while it runs.
   Installing with `-g`, which Test Lab does by default, already grants all three
   (verified on the emulator), so it first revokes the VM ones it finds granted.
   `dumpsys package` must then show all three granted to it, neither VM permission granted
   to the stub or the harness, and all three still there after a force-stop. It records
   whether the grant killed the host's process.
3. `t3_hostReachesRunning`: opens the host app, reads its capability line, taps Start, and
   follows the status line to RUNNING. A retryable error (a dropped download) gets up to two
   more Starts, and a host app that disappears is reopened.
4. `t4_doctorAndExecOverTheRelay`: taps Mint, then runs `azula compute doctor --vm` (up to
   three times) and `azula compute exec -- uname -a` from the shell with
   `AZULA_RELAY_ONLY=1`.
5. `t5_cloudStepsOverTheRelay`: taps Mint again and runs the steps of
   [`azula-cli/vm/e2e/cloud.sh`](https://github.com/Azula-App/azula-cli/blob/main/vm/e2e/cloud.sh)
   from the shell, relay-only, against the VM `default`.

Afterwards it saves diagnostics and stops the VM.

### The steps of stage 5

The azula commands are cloud.sh's. The phone's shell has no bash, python3 or `file`, so the
harness does the hashing, the ELF check and the JSON. Each step has its own outcome and
time, and one that doesn't pass never stops the next. The stage passes only if all nine do.

| Step | Scenario | Passes when |
|---|---|---|
| `start` | H1 | `compute doctor`, `vm start default` and `compute doctor --vm default` exit 0 |
| `identity` | | `exec -- uname -sm` prints exactly `Linux aarch64` |
| `streams` | H2 | exit 7, with `to-stdout` on stdout and `to-stderr` on stderr only |
| `reattach` | S4 | `seq 1 300000` through a forced drop after 400,000 bytes: exit 0, the reattach notice, and the same SHA-256 as a `seq` made here |
| `transfer` | S7 H4 | 9,000,000 random bytes pushed and pulled, each through a forced drop after 3,000,000: exit 0 and the resume notice both times, one SHA-256 for the blob, the copy pulled back and the guest's |
| `tree` | H4 | a C tree pushed with `-r`, built and run in the VM. The binary pulled back is an aarch64 ELF, and the tree pulled back with `-r` is whole |
| `detach` | H3 | `exec --detach` prints a job, `jobs` lists it, `attach` replays `tick1` to `tick3` and exits 5, `logs --tail 1` prints `tick3` |
| `survivor` | S9 H8 | the client is SIGKILLed while its job runs. The job is found by its label in `jobs --json`, and `attach` to it exits 0 with `survived` |
| `timeout` | | `exec --timeout 3s -- sleep 60` exits 124 in under 20 s |

Where it is stricter than cloud.sh:

- The resume notice must be "resuming push" (or pull). cloud.sh takes any "resum", which
  the closing line's "(resumed from …)" would also match.
- `survivor` waits until azula says the job started, then 2 s more, and kills azula itself.
  It needs the wait status of a SIGKILL. cloud.sh kills 3 s after the start, job or no job.
- `./hello` must have printed its line, and the pulled tree's `hello` and `msg.h` must be
  byte for byte the ones already here. cloud.sh looks at the exit code and that they exist.
- A client that hangs is killed at a limit of its own, and that is a failure even if the
  exit code is the one the step wants.

A step ends as one of these (`cloud.steps` in `result.json`, with `seconds` and `maxSeconds`):

| Outcome | Passed | Means |
|---|---|---|
| `PASS` | yes | |
| `PASS_THROTTLED` | yes | passed, and the relay throttled the client or the host meanwhile, which explains a slow one. Nothing is asserted less |
| `FAIL` | no | a command ran and gave the wrong answer |
| `TIMED_OUT` | no | a command was killed, at its own limit or at the end of the step's or the run's time. That says nothing about the answer it would have given |
| `NOT_RUN` | no | the run was out of time, or something kept the step from being reached |

`throttledBy` names who the relay throttled during the step, whatever its outcome: `client`
when the warning ("The relay is rate-limiting this endpoint", from iroh) is in the output of
one of the step's commands, `host` when the host app's broker log gained one. It is the
warning and nothing else, so a quick step can have it. `cloud.hostThrottleWarnings` is the
host's total. n0's public relays throttle bulk traffic at times, to about 40 KB/s each way
when measured.

### How long it takes

| | Not throttled | Throttled |
|---|---|---|
| Stages 1 to 4 | 4 min, 6 on the Pixel 11 (its image download took 167 s) | the same |
| Stage 5 | about 2 min | 9 min |
| Whole run | 6 to 8 min | 13 to 15 min |

Stages 1 to 4 are from the Test Lab runs. The throttled stage 5 is from the emulator (533 s:
`transfer` 430, `reattach` 57, everything else 46), where client and host share the device's
network, as they do on Test Lab. A throttled cloud.sh on a Pixel 10a agrees: 532 s, 485 of
them in `transfer`. The unthrottled stage 5 is an estimate: cloud.sh takes 74 to 108 s.

`transfer` gets 12 min and `reattach` 5, so throttling alone doesn't fail them. With both at
their limits, stage 5 takes 18 min and the run 22 to 24.

`--timeout 40m` covers that. Stage 5 is cut off 34 min into the run (`runLimitMin`), and the
revoke and the report need 1 to 4 min after that. So a boot that takes its whole 15 min
still leaves stage 5 about 18. What has used up more than that before stage 5 (doctor can
take 10 min of attempts) leaves its last steps `NOT_RUN` or `TIMED_OUT`, which fails the
run. Test Lab kills a run at `--timeout`, with no report and no revoke, so don't go below 40.

### The tokens

Stages 4 and 5 mint one each, the same way. The host app writes it to its own private files
(0600). The harness reads it with `run-as` into memory, and hands it to `azula` through the
stdin of an `sh` as an environment variable (never argv, a file or logcat). Everything the
harness logs or writes is scrubbed of `azc1.…` strings and of the tokens seen in the run. A
file that would still hold one isn't written.

Whatever happens in the stage, it then revokes the grant and deletes the token file. The
stage fails, even if everything else passed, unless the host app says that it revoked this
grant, the file is gone, and the host no longer takes the token (`afterRevoke` in
`result.json`: `REFUSED`). `UNKNOWN`, when the host couldn't be reached to ask, is recorded
but doesn't fail the stage. The end of the run tries once more for a token a stage left over.
If Test Lab kills the run, nothing can be revoked. A token lasts 6 hours, and its host goes
with the phone's data.

## Reading the result

`vmhost_outcome` in the instrumentation results and `outcome` in `result.json`:

| Outcome | Test cases | Means |
|---|---|---|
| `PASS` | all five passed | the VM reached RUNNING, doctor, exec and every step of cloud.sh passed over the relay, and both tokens were revoked |
| `CANNOT_HOST` | `t3` (or `t2`) skipped with `CANNOT_HOST: <reason>`, or failed with that message if `requireHost=true` | a finding about the device. The reason is the app's own sentence, or that the build doesn't define or grant the VM permissions |
| `FAIL` | one failed with `FAILED at <stage>: <reason>` | the host or the setup is broken; `reason` says how far it got. A failure outranks `CANNOT_HOST` |
| missing | instrumentation crashed or timed out | infrastructure; look at Test Lab's own logcat |

gcloud exits 10 if a test case didn't pass. Without `requireHost=true`, a device that can't
host has no failed case, so read `outcome`, not the exit code.

Test Lab keeps the pulled directory with the run's results:

| File | What |
|---|---|
| `result.json` | the verdict, each stage's outcome and time, the device, the grants before and after, the host's phases with durations, doctor's stages, `uname`, each step of stage 5 with its outcome and time (`cloud.steps`), what became of the tokens |
| `timeline.tsv` | the host app's status line over time |
| `azula-doctor.txt`, `azula-exec.txt` | output of every attempt |
| `azula-cloud.txt` | every command of stage 5: exit code, time, the end of what it printed |
| `log-{host,provision,broker,proxy,console}.txt` | the host app's logs, read with `run-as` |
| `host-state.json`, `host-files.txt` | its persisted state and file sizes |
| `device-{before,after}.txt`, `getprop.txt` | battery, thermal, storage, memory, AVF (`vm info`), permissions |
| `logcat.txt` | the host's and the harness's lines |
| `*.png` | the host app when opened and after Start |

## Try it on an emulator

```sh
vmhost-ftl/run-emulator.sh [emulator-5554] [-e bootTimeoutMin 5]
```

It refuses anything that isn't an emulator. An emulator has no hypervisor, so the right
verdict is `CANNOT_HOST` from `t3`, with `t1` and `t2` passing and `t4` and `t5` not run. The
report lands in `out/emulator/`.

## What has been proven where

- Test Lab, Pixel 8 and 9 (Android 16) and Pixel 11 (Android 17), 2026-11-26: all five
  stages, PASS, with the host library, seed and `azula` of azula-cli 0.4.3. Only `reattach`
  and `transfer` were throttled by the relay (`transfer` 165 to 167 s, the runs 333 to
  408 s). Every token was revoked and then refused.
- Test Lab, Pixel 8 and 9, 2026-11-24, with azula-cli aac8fe9's guest and broker: all five
  stages, PASS. The relay throttled all nine steps on the Pixel 8 and four on the Pixel 9
  (`transfer` 426 s and 421 s).
- The emulator, against a simulated host (a stand-in for the host app's screen, the real
  broker over n0's relay, and `azula-guest` in a Debian VM): stage 5 passes, throttled, and
  fails when it should: wrong answers, faults that never fire, corrupted data, a client that
  hangs, a token that isn't revoked.

Test Lab itself is deprecated and shuts down on 2027-09-30. Google points to its Developer
Device Platform as the replacement.
