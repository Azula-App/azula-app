#!/usr/bin/env bash
# Produces the two native build inputs of the VM host and places them where the
# Android modules package them. Both are build outputs and gitignored:
#
#   <module>/jniLibs/arm64-v8a/libazula_host.so   the JNI compute broker
#   <module>/assets/seed.iso                       the cloud-init seed for the VM
#
# for <module> in android-app and vmhost-testapp.
#
# Usage: scripts/vmhost-artifacts.sh [--release [<version>]]
#   Default: build from the sibling azula-cli repo (../azula-cli). Needs the
#   Android NDK, `rustup target add aarch64-linux-android`, and macOS (hdiutil,
#   which build-seed.sh uses).
#   --release [<version>]: download azula-cli's release assets instead
#   (azula-host-android-<v>-aarch64.tar.gz and azula-avf-seed-<v>.iso from
#   github.com/Azula-App/azula-cli) and verify their .sha256 files. The version
#   defaults to the pin in vmhost.version. Not yet testable end to end: no azula-cli
#   release carries these assets until the next v* tag.
#   Env: AZULA_CLI_DIR  override the azula-cli directory (default: ../azula-cli)
#        AZULA_HOST_SO / AZULA_SEED_ISO  use prebuilt files instead of building
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLI=${AZULA_CLI_DIR:-$ROOT/../azula-cli}
[ -d "$CLI" ] || CLI=$ROOT/../../azula-cli   # when run from inside .worktrees/<name>
if [ "${1:-}" != "--release" ] && [ ! -d "$CLI" ]; then echo "azula-cli not found (set AZULA_CLI_DIR)" >&2; exit 1; fi

SO=${AZULA_HOST_SO:-}
ISO=${AZULA_SEED_ISO:-}

fetch_release() {
  local v=$1 base dl
  base="https://github.com/Azula-App/azula-cli/releases/download/v$v"
  dl=$(mktemp -d)
  trap 'rm -rf "$dl"' RETURN
  local tgz="azula-host-android-$v-aarch64.tar.gz" iso="azula-avf-seed-$v.iso"
  for f in "$tgz" "$tgz.sha256" "$iso" "$iso.sha256"; do
    curl -fsSL -o "$dl/$f" "$base/$f"
  done
  (cd "$dl" && shasum -a 256 -c "$tgz.sha256" && shasum -a 256 -c "$iso.sha256") >&2
  tar -xzf "$dl/$tgz" -C "$dl" libazula_host.so
  mkdir -p "$ROOT/.vmhost-cache"
  cp "$dl/libazula_host.so" "$ROOT/.vmhost-cache/libazula_host.so"
  cp "$dl/$iso" "$ROOT/.vmhost-cache/seed.iso"
  SO=$ROOT/.vmhost-cache/libazula_host.so
  ISO=$ROOT/.vmhost-cache/seed.iso
}

if [ "${1:-}" = "--release" ]; then
  VER=${2:-$(grep -v '^#' "$ROOT/vmhost.version" | head -n 1 | tr -d '[:space:]')}
  fetch_release "$VER"
fi
if [ -z "$SO" ]; then
  SO=$("$CLI/android-host-jni/build-android.sh" | sed -n 1p)
fi
if [ -z "$ISO" ]; then
  ISO=$("$CLI/vm/avf-seed/build-seed.sh" | tail -n 1)
fi
[ -f "$SO" ] || { echo "missing $SO" >&2; exit 1; }
[ -f "$ISO" ] || { echo "missing $ISO" >&2; exit 1; }

for m in android-app vmhost-testapp; do
  mkdir -p "$ROOT/$m/jniLibs/arm64-v8a" "$ROOT/$m/assets"
  cp "$SO" "$ROOT/$m/jniLibs/arm64-v8a/libazula_host.so"
  cp "$ISO" "$ROOT/$m/assets/seed.iso"
done
echo "libazula_host.so: $(wc -c <"$SO") bytes; seed.iso: $(wc -c <"$ISO") bytes"
