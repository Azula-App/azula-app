#!/usr/bin/env bash
set -euo pipefail

# Freezes CHANGELOG.md's [Unreleased] section as NEW_VERSION, then re-opens an
# empty [Unreleased] above it. Run by release.yml BEFORE it tags, because the
# commit this produces is the commit the tag points at -- publish.yml reads every
# repo file from the tag's tree, so notes that are not in the tag do not exist as
# far as a build is concerned.
#
# Validates first and refuses to touch the file if the section is not shippable.
# Failing here is the entire point: no tag has been created yet, nothing has been
# uploaded, and the fix is an ordinary commit followed by a re-dispatch. The
# alternative -- discovering it after one of the two stores has been fed -- is the
# failure this exists to prevent.
#
# Running it twice in a row fails on the emptiness check, since the [Unreleased]
# it just re-opened has nothing in it.
#
#   NEW_VERSION=0.0.8 bash .github/scripts/promote_changelog.sh
#
# RELEASE_DATE (YYYY-MM-DD) overrides the stamped date; it exists so tests are
# not a function of the day they run on.

new_version="${NEW_VERSION:?NEW_VERSION not set}"
release_date="${RELEASE_DATE:-$(date -u +%Y-%m-%d)}"

# CHANGELOG_FILE is the test seam; without it, behave like the sibling release
# scripts and work from the repo root. Guarded rather than unconditional so a
# failed `git rev-parse` can't leave us running `cd ""` and operating on whatever
# relative path the caller happened to be standing in.
if [ -z "${CHANGELOG_FILE:-}" ]; then
  cd "$(git rev-parse --show-toplevel)"
fi
# shellcheck source=changelog_lib.sh
source "$(dirname "$0")/changelog_lib.sh"

file="$(changelog_path)"
[ -f "$file" ] || { echo "$file not found" >&2; exit 1; }

if grep -qE "^## \[$new_version\]" "$file"; then
  echo "$file already has a [$new_version] section -- was this release already cut?" >&2
  exit 1
fi

# ── Validate [Unreleased] ───────────────────────────────────────────────────
# Order matters: headings first, so a typo'd `### Fixes` is reported as the typo
# it is rather than as a missing tier further down.
rc=0
changelog_check_headings Unreleased || rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

notes=$(mktemp)
trap 'rm -f "$notes"' EXIT

rc=0
changelog_store_notes Unreleased > "$notes" || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "Nothing to ship: [Unreleased] needs a \"### Store notes\" block holding the" >&2
  echo "text Play, TestFlight, and the App Store will show. If this release genuinely" >&2
  echo "changes nothing a user can see, say so in one line." >&2
  exit "$rc"
fi

rc=0
changelog_check_limit "$notes" || rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

rc=0
changelog_entries Unreleased > /dev/null || rc=$?
if [ "$rc" -ne 0 ]; then
  echo "[Unreleased] has store notes but no changelog entries. Both tiers are" >&2
  echo "required -- the store block is the summary, and the entries are the record." >&2
  exit "$rc"
fi

changelog_warn_non_observable Unreleased

# ── Promote ─────────────────────────────────────────────────────────────────
# Replaces the [Unreleased] heading with a fresh empty section plus the dated
# heading; everything that was under [Unreleased] stays put and is now under
# [NEW_VERSION]. Only the FIRST match is rewritten, so a literal "## [Unreleased]"
# inside some earlier version's store block could never be mistaken for it.
tmp=$(mktemp)
awk -v ver="$new_version" -v date="$release_date" '
  !done && /^## \[Unreleased\]/ {
    print "## [Unreleased]"
    print ""
    print "### Store notes"
    print ""
    print "```text"
    print "```"
    print ""
    print "## [" ver "] - " date
    done = 1
    next
  }
  { print }
  END { exit(done ? 0 : 1) }
' "$file" > "$tmp" || { rm -f "$tmp"; echo "No \"## [Unreleased]\" heading in $file" >&2; exit 1; }

mv "$tmp" "$file"

echo "Promoted [Unreleased] -> [$new_version] - $release_date"
echo "--- store notes for $new_version ---"
cat "$notes"
echo "--- end ---"
