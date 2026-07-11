#!/usr/bin/env bash
set -euo pipefail

# Turns on Android release signing by appending the signing block to
# android-app/module.yaml.
#
# Why this isn't just committed: Amper's signing is not variant-scoped, so with
# `signing.enabled: true` in the file, *every* build -- including a plain debug
# `./kotlin build -m android-app` -- fails on a machine that has no
# keystore.properties. That would break every contributor who never cuts a release.
# So the release run enables it, and the committed file stays buildable by anyone.
#
# Expects android-app/keystore.properties to exist already (CI writes it from
# secrets). Idempotent.

cd "$(git rev-parse --show-toplevel)"

module="android-app/module.yaml"
props="android-app/keystore.properties"

[ -f "$module" ] || { echo "$module not found!" >&2; exit 1; }
[ -f "$props" ] || {
  echo "$props not found -- write it before enabling signing, or the build will fail." >&2
  exit 1
}

if grep -q '^    signing:' "$module"; then
  echo "Signing already enabled in $module; nothing to do."
  exit 0
fi

anchor='^    namespace: app\.azula$'
grep -qE "$anchor" "$module" || {
  echo "Anchor line 'namespace: app.azula' not found in $module -- the settings.android" >&2
  echo "block must have changed shape. Fix this script rather than shipping unsigned." >&2
  exit 1
}

# awk, not sed: GNU and BSD sed disagree about multi-line inserts.
awk -v anchor="$anchor" '
  { print }
  !inserted && $0 ~ anchor {
    print "    signing:"
    print "      enabled: true"
    print "      propertiesFile: keystore.properties"
    inserted = 1
  }
' "$module" > "$module.tmp"

mv "$module.tmp" "$module"

echo "Enabled signing in $module:"
grep -n -A2 '^    signing:' "$module"
