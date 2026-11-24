#!/usr/bin/env bash
# Builds everything Test Lab needs to run the VM host acceptance test, into vmhost-ftl/out/:
#
#   ftl-stub.apk        --app              the instrumented package; no code of its own
#   ftl-harness.apk     --test             the instrumentation
#   vmhost-testapp.apk  --additional-apks  the host app the test grants and drives
#   azula               --other-files      static arm64 azula, run from the device's shell
#   SHA256SUMS
#
# Usage: vmhost-ftl/build.sh [--record-deps]
#   Needs the host app's native artifacts (scripts/vmhost-artifacts.sh, run here if missing) and
#   the static azula (../azula-cli's vm/android-host/build-broker.sh, run here if missing).
#   --record-deps  rewrite deps.sha256 from the current resolution instead of checking it.
#   Env: AZULA_CLI_DIR   the azula-cli directory (default: ../azula-cli)
#        AZULA_BIN       use this static aarch64 azula instead
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
CLI=${AZULA_CLI_DIR:-$ROOT/../azula-cli}
[ -d "$CLI" ] || CLI=$ROOT/../../azula-cli   # when run from inside .worktrees/<name>
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -n 1)
CACHE=${KOTLIN_M2_CACHE:-$HOME/Library/Caches/JetBrains/Kotlin/.m2.cache}
OUT=$HERE/out
HOST_PKG=app.azula.vmhost.test STUB_PKG=app.azula.vmhost.ftl.stub HARNESS_PKG=app.azula.vmhost.ftl.harness
say() { printf 'vmhost-ftl: %s\n' "$*"; }
die() { say "$*" >&2; exit 1; }

# The Android debug keystore signs everything, so builds are interchangeable (and installable over
# each other). AGP would create a missing one; this doesn't do that silently.
[ -f "$HOME/.android/debug.keystore" ] || die "no ~/.android/debug.keystore: make one first (any Android build does)"

cd "$ROOT"
if [ ! -f vmhost-testapp/jniLibs/arm64-v8a/libazula_host.so ] || [ ! -f vmhost-testapp/assets/seed.iso ]; then
  say "host native artifacts missing; building them"
  scripts/vmhost-artifacts.sh
fi

BIN=${AZULA_BIN:-$CLI/target/aarch64-unknown-linux-musl/release/azula}
if [ ! -f "$BIN" ]; then
  [ -z "${AZULA_BIN:-}" ] || die "no $BIN"
  say "static arm64 azula missing; building it"
  BIN=$(sh "$CLI/vm/android-host/build-broker.sh")
fi
case "$(file -b "$BIN")" in
  *"ELF 64-bit"*aarch64*"statically linked"*) ;;
  *) die "$BIN isn't a static arm64 ELF: $(file -b "$BIN")" ;;
esac

./kotlin build -m ftl-stub -m ftl-harness -m vmhost-testapp
apk() { echo "build/tasks/_$1_buildAndroidDebug/gradle-project-debug.apk"; }
for m in ftl-stub ftl-harness vmhost-testapp; do [ -f "$(apk $m)" ] || die "no $(apk $m)"; done

# The harness's dependencies: exactly the recorded set, with the recorded bytes.
resolved() {
  grep -oE '[^" ]*\.m2\.cache[^" ]*\.(aar|jar)' \
    build/tasks/_ftl-harness_buildAndroidDebug/gradle-project/settings.gradle.kts |
    sed 's|.*\.m2\.cache/||' | sort -u
}
if [ "${1:-}" = "--record-deps" ]; then
  { grep '^#' "$HERE/deps.sha256"; (cd "$CACHE" && resolved_list=$(cd "$ROOT" && resolved) && shasum -a 256 $resolved_list); } >"$HERE/deps.sha256.new"
  mv "$HERE/deps.sha256.new" "$HERE/deps.sha256"
  say "recorded $(grep -vc '^#' "$HERE/deps.sha256") dependencies"
else
  diff <(resolved) <(grep -v '^#' "$HERE/deps.sha256" | awk '{print $2}') >&2 ||
    die "the harness resolves other artifacts than deps.sha256 lists (see the diff)"
  (cd "$CACHE" && grep -v '^#' "$HERE/deps.sha256" | shasum -a 256 -c --quiet) || die "a dependency's bytes changed"
fi

rm -rf "$OUT"; mkdir -p "$OUT"
for m in ftl-stub ftl-harness vmhost-testapp; do cp "$(apk $m)" "$OUT/$m.apk"; done
cp "$BIN" "$OUT/azula"

# What Test Lab and Android will hold us to.
cert() { "$BT/apksigner" verify --print-certs "$1" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1; }
pkg() { "$BT/aapt2" dump packagename "$1"; }
want() { [ "$2" = "$3" ] || die "$1: $2, expected $3"; }
want "stub package" "$(pkg "$OUT/ftl-stub.apk")" $STUB_PKG
want "harness package" "$(pkg "$OUT/ftl-harness.apk")" $HARNESS_PKG
want "host package" "$(pkg "$OUT/vmhost-testapp.apk")" $HOST_PKG
[ -n "$(cert "$OUT/ftl-stub.apk")" ] || die "ftl-stub.apk isn't signed"
want "harness certificate" "$(cert "$OUT/ftl-harness.apk")" "$(cert "$OUT/ftl-stub.apk")"
# Whole output first: `grep -q` would close the pipe early, which pipefail reports as a failure.
has() { case "$("${@:2}")" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
has "\"$STUB_PKG\"" "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$OUT/ftl-harness.apk" ||
  die "the harness doesn't instrument $STUB_PKG"
has classes.dex unzip -l "$OUT/ftl-stub.apk" || die "the stub has no dex: Test Lab rejects it (NO_CODE_APK)"
for p in MANAGE_VIRTUAL_MACHINE USE_CUSTOM_VIRTUAL_MACHINE; do
  has "android.permission.$p'" "$BT/aapt2" dump permissions "$OUT/vmhost-testapp.apk" || die "the host app doesn't request $p"
  for a in ftl-stub ftl-harness; do
    ! has "android.permission.$p'" "$BT/aapt2" dump permissions "$OUT/$a.apk" || die "$a requests $p; only the host may"
  done
done
has lib/arm64-v8a/libazula_host.so unzip -l "$OUT/vmhost-testapp.apk" || die "the host app has no libazula_host.so"
has assets/seed.iso unzip -l "$OUT/vmhost-testapp.apk" || die "the host app has no seed.iso"

(cd "$OUT" && shasum -a 256 ftl-stub.apk ftl-harness.apk vmhost-testapp.apk azula >SHA256SUMS && cat SHA256SUMS)
say "ready in $OUT (README.md has the Test Lab commands)"
