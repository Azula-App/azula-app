#!/usr/bin/env bash
set -euo pipefail

# Writes the store-notes text for RELEASE_VERSION to $1, for publish.yml to hand
# to Google Play and the App Store Connect API. Run from a checkout of the TAG
# being built, so the text is a pure function of that tag.
#
#   RELEASE_VERSION=0.0.8 bash .github/scripts/release_notes.sh whatsnew/whatsnew-en-US
#
# Reports `status=present` or `status=absent` on $GITHUB_OUTPUT (falling back to
# /dev/null when unset, like the version scripts) so the caller can tell the two
# harmless-looking outcomes apart:
#
#   absent  the tag's tree has no CHANGELOG.md at all -- it predates the file
#           (v0.0.1 .. v0.0.7). Warn and ship the binaries; re-publishing an old
#           tag has to keep working.
#   FAILURE the file is there but this version's section or store block is
#           missing, empty, or malformed. Someone hand-tagged, or the promotion
#           did not run. Notes were expected, so stop -- guessing at release
#           notes is worse than not shipping.
#
# The text is copied byte for byte: `changelog_store_notes` streams it and this
# redirects it, with no command substitution anywhere in the path, because
# `$(...)` eats trailing newlines and "verbatim" has to mean verbatim.

version="${RELEASE_VERSION:?RELEASE_VERSION not set}"
out="${1:?usage: release_notes.sh <output-path>}"

if [ -z "${CHANGELOG_FILE:-}" ]; then
  cd "$(git rev-parse --show-toplevel)"
fi
# shellcheck source=changelog_lib.sh
source "$(dirname "$0")/changelog_lib.sh"

emit() { echo "$1" >> "${GITHUB_OUTPUT:-/dev/null}"; }

file="$(changelog_path)"
if [ ! -f "$file" ]; then
  echo "::warning::No CHANGELOG.md in this tag's tree -- shipping $version without release notes."
  emit "status=absent"
  exit 0
fi

rc=0
changelog_check_headings "$version" || rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

mkdir -p "$(dirname "$out")"

rc=0
changelog_store_notes "$version" > "$out" || rc=$?
if [ "$rc" -ne 0 ]; then
  rm -f "$out"
  echo "::error::CHANGELOG.md exists but has no usable [$version] store-notes block." >&2
  echo "Expected the section promote_changelog.sh writes when the tag is cut." >&2
  exit "$rc"
fi

rc=0
changelog_check_limit "$out" || rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

emit "status=present"

echo "--- release notes for $version ---"
cat "$out"
echo "--- end ---"
