#!/bin/sh
# Build a copy of the Android app that installs beside the real one, and that the
# launcher tells apart from it: application id app.azula.<name>, label
# "Azula <name>", and its own icon colour. The checkout is put back afterwards.
#
# Usage: scripts/side-by-side.sh <name> [#rrggbb] [extra ./kotlin build args, e.g. -v release]
#   scripts/side-by-side.sh dev          -> build/side-by-side/app.azula.dev.apk, blue
#   scripts/side-by-side.sh qa '#7c3aed' -> purple
#
# The host-compute entry needs the VM host's native files; run
# scripts/vmhost-artifacts.sh first, as for any Android build of the app.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

NAME=${1:-}
case "$NAME" in
  '' | *[!a-z0-9]* | [0-9]*) echo "usage: $0 <name: lowercase letters and digits> [#rrggbb] [build args]" >&2; exit 2 ;;
esac
shift
COLOR=
case "${1:-}" in
  \#??????) COLOR=$1; shift ;;
esac
if [ -z "$COLOR" ]; then
  # The real app is near-black (#070709) and the mock near-white. Each copy gets a
  # bright ground of its own, fixed per name so it looks the same every build.
  case "$NAME" in
    dev) COLOR='#1d4ed8' ;;  # blue
    test) COLOR='#b45309' ;; # amber
    *)
      set -- '#be123c' '#047857' '#7c3aed' '#0e7490' '#a16207' '#c026d3' "$@"
      i=$(( $(printf '%s' "$NAME" | cksum | cut -d' ' -f1) % 6 + 1 ))
      eval "COLOR=\${$i}"
      shift 6
      ;;
  esac
fi

MODULE=android-app/module.yaml
MANIFEST=android-app/src/AndroidManifest.xml
COLORS=android-app/res/values/ic_launcher_background.xml
REAL_COLOR=$(sed -n 's/.*name="ic_launcher_background">\(#[0-9a-fA-F]*\)<.*/\1/p' "$COLORS")
grep -q '^    applicationId: app.azula$' "$MODULE" || { echo "$MODULE: no 'applicationId: app.azula' line" >&2; exit 1; }
grep -q 'android:label="Azula"' "$MANIFEST" || { echo "$MANIFEST: no 'android:label=\"Azula\"'" >&2; exit 1; }
[ -n "$REAL_COLOR" ] || { echo "$COLORS: no ic_launcher_background colour" >&2; exit 1; }

# In place, leaving no backup behind (the resource merger reads every file).
subst() { sed -i.bak "$2" "$1" && rm -f "$1.bak"; }

# Undo only our own three lines, so other edits made meanwhile survive.
put_back() {
  subst "$MODULE" "s/^    applicationId: app.azula.$NAME\$/    applicationId: app.azula/"
  subst "$MANIFEST" "s/android:label=\"Azula $NAME\"/android:label=\"Azula\"/"
  subst "$COLORS" "s/name=\"ic_launcher_background\">$COLOR</name=\"ic_launcher_background\">$REAL_COLOR</"
}
trap put_back EXIT INT TERM
subst "$MODULE" "s/^    applicationId: app.azula\$/    applicationId: app.azula.$NAME/"
subst "$MANIFEST" "s/android:label=\"Azula\"/android:label=\"Azula $NAME\"/"
subst "$COLORS" "s/name=\"ic_launcher_background\">$REAL_COLOR</name=\"ic_launcher_background\">$COLOR</"

STARTED=$(mktemp)
./kotlin build -m android-app "$@"
APK=$(find build/tasks -path '*_android-app_buildAndroid*' -name '*.apk' -newer "$STARTED" 2>/dev/null | head -n 1)
rm -f "$STARTED"
[ -n "$APK" ] || { echo "the build made no new APK" >&2; exit 1; }
mkdir -p build/side-by-side
OUT=build/side-by-side/app.azula.$NAME.apk
cp "$APK" "$OUT"
echo "$OUT: app.azula.$NAME, \"Azula $NAME\", icon $COLOR"
