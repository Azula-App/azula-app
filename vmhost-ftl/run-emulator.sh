#!/usr/bin/env bash
# Runs the acceptance test on a local emulator the way Test Lab would: installs the three APKs,
# pushes azula without its exec bit, runs the instrumentation, pulls the report. An emulator has
# no hypervisor, so the right verdict there is CANNOT_HOST (with grants and azula passing).
#
# Usage: vmhost-ftl/run-emulator.sh [SERIAL] [-e KEY VALUE]...
#   SERIAL defaults to emulator-5554. Anything but an emulator is refused.
#   KEEP=1       leaves the three packages installed afterwards.
#   GRANT_ALL=1  installs with -g, as Test Lab does by default. Without it the test makes every grant.
# Exit: 0 PASS or CANNOT_HOST, 1 FAIL or no verdict, 2 not runnable.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/out
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
SERIAL=emulator-5554
case "${1:-}" in
  emulator-*) SERIAL=$1; shift ;;
  -* | "") ;;
  *) echo "not an emulator serial: $1" >&2; exit 2 ;;
esac
adb() { "$SDK/platform-tools/adb" -s "$SERIAL" "$@"; }
[ "$(adb shell getprop ro.boot.qemu | tr -d '\r')" = 1 ] || { echo "$SERIAL isn't an emulator" >&2; exit 2; }
for f in ftl-stub.apk ftl-harness.apk vmhost-testapp.apk azula; do
  [ -f "$OUT/$f" ] || { echo "no $OUT/$f: run build.sh" >&2; exit 2; }
done

DEVDIR=/sdcard/Download/vmhost-ftl
PKGS="app.azula.vmhost.ftl.stub app.azula.vmhost.ftl.harness app.azula.vmhost.test"
cleanup() {
  adb shell rm -rf /data/local/tmp/azula "$DEVDIR" || true
  if [ "${KEEP:-0}" != 1 ]; then
    for p in $PKGS; do adb uninstall "$p" >/dev/null 2>&1 || true; done
  fi
}
trap cleanup EXIT

for p in $PKGS; do adb uninstall "$p" >/dev/null 2>&1 || true; done
adb shell rm -rf "$DEVDIR"
for a in ftl-stub ftl-harness vmhost-testapp; do
  if [ "${GRANT_ALL:-0}" = 1 ]; then adb install -g "$OUT/$a.apk"; else adb install "$OUT/$a.apk"; fi
done
adb push "$OUT/azula" /data/local/tmp/azula >/dev/null
adb shell chmod 644 /data/local/tmp/azula

rm -rf "$OUT/emulator"
mkdir -p "$OUT/emulator"
adb shell am instrument -w -r "$@" app.azula.vmhost.ftl.harness/androidx.test.runner.AndroidJUnitRunner |
  tr -d '\r' | tee "$OUT/emulator/instrument.txt"
adb pull "$DEVDIR" "$OUT/emulator/" >/dev/null
echo "report: $OUT/emulator/vmhost-ftl"
v=$(sed -n 's/^INSTRUMENTATION_RESULT: vmhost_outcome=//p' "$OUT/emulator/instrument.txt")
echo "verdict: ${v:-none}"
case "$v" in
  PASS | CANNOT_HOST) ;;
  *) exit 1 ;;
esac
