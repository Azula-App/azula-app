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

- `--timeout` is needed: the default is 15 min, and Test Lab's limit on physical devices
  is 45 min. A run needs about 4 min when all goes well. The worst case is 15 min to
  RUNNING plus about 10 min of doctor attempts.
- Don't add `--use-orchestrator`. The stages share state and run in one process.
- Each run downloads the 300 MB Debian image over the device's network.

### Arguments (`--environment-variables`, or `-e` on `am instrument`)

| Name | Default | Meaning |
|---|---|---|
| `requireHost` | `false` | `true` turns "this device can't host" into a failure instead of a skip. Use it for devices that should host |
| `bootTimeoutMin` | `15` | how long Start may take to reach RUNNING |
| `azulaPath` | `/data/local/tmp/azula` | where `--other-files` put the binary |
| `outDir` (or `additionalTestOutputDir`) | `/sdcard/Download/vmhost-ftl` | where the report goes; keep `--directories-to-pull` in step |

## What it does

Four test cases of `HostAcceptanceTest`, in order. A later one is skipped, with the reason,
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
   `AZULA_RELAY_ONLY=1`. It revokes the grant and deletes the token file whatever happened.

Afterwards it saves diagnostics and stops the VM.

### The token

The host app writes it to its own private files (0600). The harness reads it with `run-as`
into memory, hands it to `azula` through the stdin of an `sh` as an environment variable
(never argv, a file or logcat), and revokes it at the end. Everything the harness logs or
writes is scrubbed of `azc1.…` strings and of the token seen in the run. A file that would
still hold one isn't written.

## Reading the result

`vmhost_outcome` in the instrumentation results and `outcome` in `result.json`:

| Outcome | Test cases | Means |
|---|---|---|
| `PASS` | all four passed | the VM reached RUNNING, and doctor and exec passed over the relay |
| `CANNOT_HOST` | `t3` (or `t2`) skipped with `CANNOT_HOST: <reason>`, or failed with that message if `requireHost=true` | a finding about the device. The reason is the app's own sentence, or that the build doesn't define or grant the VM permissions |
| `FAIL` | one failed with `FAILED at <stage>: <reason>` | the host or the setup is broken; `reason` says how far it got. A failure outranks `CANNOT_HOST` |
| missing | instrumentation crashed or timed out | infrastructure; look at Test Lab's own logcat |

gcloud exits 10 if a test case didn't pass. Without `requireHost=true`, a device that can't
host has no failed case, so read `outcome`, not the exit code.

Test Lab keeps the pulled directory with the run's results:

| File | What |
|---|---|
| `result.json` | the verdict, each stage's outcome and time, the device, the grants before and after, the host's phases with durations, doctor's stages, `uname` |
| `timeline.tsv` | the host app's status line over time |
| `azula-doctor.txt`, `azula-exec.txt` | output of every attempt |
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
verdict is `CANNOT_HOST` from `t3`, with `t1` and `t2` passing. The report lands in
`out/emulator/`.

## Not proven until a real device runs it

AVF booting the guest, RUNNING, doctor and exec against that guest, and whether Test Lab
accepts the host app's VM permissions (it can reject a matrix with `FORBIDDEN_PERMISSIONS`)
and lets its shell grant them. Those need a first Test Lab run.

Test Lab itself is deprecated and shuts down on 2027-09-30. Google points to its Developer
Device Platform as the replacement.
