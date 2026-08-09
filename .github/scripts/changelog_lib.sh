#!/usr/bin/env bash
# Parses CHANGELOG.md for the release (tag-cutting) and publish (tag-building)
# workflows. Sourced, not executed -- the sibling of version_lib.sh.
#
# The file's format is defined HERE and nowhere else. Each version section is
#
#   ## [Unreleased]           or   ## [X.Y.Z] - YYYY-MM-DD
#   ### Store notes                one fenced block, shipped to the stores VERBATIM
#   ### Added|Changed|...          the record, as `- ` entries
#
# and runs to the next `## ` or EOF.
#
# Every parser below is FENCE-AWARE, and that is the whole trick: the store-notes
# block is payload, not markup, so a store note that happens to begin with `- `,
# `#`, or `## ` must never be read as structure. Getting this wrong would not
# fail loudly -- it would quietly ship the wrong text -- so each awk program
# tracks the fence state even when it does not otherwise care about fences.
#
# The store-notes body reaches its consumer through PIPES, never through a
# command substitution: `$(...)` strips trailing newlines, and "verbatim" has to
# mean verbatim (see specs/release-notes/, "The store text is published
# verbatim"). Callers that need the text on disk should redirect, not capture.
#
# Functions return distinct non-zero codes so a caller can act on each case --
# `publish.yml` in particular must tell "this tag predates the changelog" (skip,
# warn, ship) from "this tag should have notes and doesn't" (fail). Under
# `set -e` that means calling them as `if ! changelog_x; then` or
# `changelog_x || rc=$?`, never bare.

# Google Play caps release notes at 500 characters per locale; TestFlight and the
# App Store allow 4000 each. One text has to satisfy the smallest, so 500 is the
# only limit there is, and it applies to the store block alone -- never to the
# changelog body underneath it.
CHANGELOG_STORE_LIMIT_BYTES=500

# 1 and 2 stay free for the generic "something broke" / "bad usage" meanings.
CHANGELOG_NO_FILE=3           # no CHANGELOG.md at all (a tag predating this file)
CHANGELOG_NO_SECTION=4        # file exists, no section for that version
CHANGELOG_EMPTY_SECTION=5     # section exists but holds nothing
CHANGELOG_MALFORMED=6         # unrecognized `### ` heading, or an unclosed fence
CHANGELOG_NO_STORE_BLOCK=7    # no fenced block under `### Store notes`
CHANGELOG_EMPTY_STORE_BLOCK=8 # the block is there but blank
CHANGELOG_NO_ENTRIES=9        # no `- ` entries under any category heading
CHANGELOG_TOO_LONG=10         # store block over CHANGELOG_STORE_LIMIT_BYTES

# The six Keep a Changelog categories, plus our store-notes block. Anything else
# at `### ` is rejected rather than ignored: a typo'd `### Fixes` would otherwise
# silently drop every entry beneath it, and the release would ship missing notes
# with nothing anywhere reporting it.
_CHANGELOG_CATEGORIES='Added Changed Deprecated Removed Fixed Security'

# Recognizes the heading that opens VER's section, in both spellings the file
# uses: a bare `## [Unreleased]` and a dated `## [X.Y.Z] - 2026-08-14`. String
# comparison, not a regex, so a version's dots need no escaping.
_CHANGELOG_AWK_PRELUDE='
function section_head(line, v,   t) {
  t = "## [" v "]"
  return (line == t || index(line, t " - ") == 1)
}
'

changelog_path() {
  if [ -n "${CHANGELOG_FILE:-}" ]; then
    echo "$CHANGELOG_FILE"
  else
    echo "$(git rev-parse --show-toplevel)/CHANGELOG.md"
  fi
}

# Streams VER's section body (everything under its heading, up to the next `## `)
# to stdout. The building block every other reader is layered on.
_changelog_section_lines() {
  local version="$1" file
  file="$(changelog_path)"
  [ -f "$file" ] || return $CHANGELOG_NO_FILE

  awk -v ver="$version" "$_CHANGELOG_AWK_PRELUDE"'
    {
      isfence = ($0 ~ /^```/)
      # A `## ` heading only ends (or opens) a section when it is real markup --
      # inside the store-notes fence it is just a character a user will read.
      if (!fence && !isfence && $0 ~ /^## /) {
        if (insec) { insec = 0 }
        else if (section_head($0, ver)) { insec = 1; found = 1; next }
      }
      if (isfence) fence = 1 - fence
      if (insec) print
    }
    END { exit(found ? 0 : 1) }
  ' "$file" || return $CHANGELOG_NO_SECTION
}

# Emits VER's section body, having first checked it is neither empty nor
# malformed. Use this when you want the whole section; the two functions below
# when you want one tier of it.
changelog_section() {
  local version="$1"
  local -a st

  if ! changelog_check_headings "$version"; then
    return $?
  fi

  _changelog_section_lines "$version" | awk '{ print } /[^[:space:]]/ { n++ } END { exit(n ? 0 : 1) }'
  st=("${PIPESTATUS[@]}")
  [ "${st[0]}" -eq 0 ] || return "${st[0]}"
  [ "${st[1]}" -eq 0 ] || return $CHANGELOG_EMPTY_SECTION
}

# Rejects any `### ` heading that is not one of ours. Runs before the readers
# below so a typo is reported as a typo rather than as a missing tier.
changelog_check_headings() {
  local version="$1"
  local -a st

  _changelog_section_lines "$version" |
    awk -v ver="$version" -v cats="$_CHANGELOG_CATEGORIES" '
      BEGIN { split(cats, a, " "); for (i in a) ok[a[i]] = 1; ok["Store notes"] = 1 }
      {
        if ($0 ~ /^```/) { fence = 1 - fence; next }
        if (fence) next
        if ($0 ~ /^### /) {
          h = substr($0, 5)
          if (!(h in ok)) {
            printf "[%s]: unrecognized heading \"%s\" -- expected \"Store notes\" or one of: %s\n", \
              ver, h, cats > "/dev/stderr"
            bad = 1
          }
        }
      }
      END { exit(bad ? 1 : 0) }
    '
  st=("${PIPESTATUS[@]}")
  [ "${st[0]}" -eq 0 ] || return "${st[0]}"
  [ "${st[1]}" -eq 0 ] || return $CHANGELOG_MALFORMED
}

# Streams the store-notes fence body for VER to stdout, byte for byte. This is
# the text the stores receive; nothing between here and the upload rewrites it.
changelog_store_notes() {
  local version="$1"
  local -a st

  _changelog_section_lines "$version" |
    awk '
      # 0: before the heading  1: heading seen, waiting for the fence
      # 2: inside the fence    3: done
      state == 0 { if ($0 == "### Store notes") state = 1; next }
      state == 1 {
        if ($0 ~ /^```/) { state = 2; sawfence = 1; next }
        if ($0 ~ /^#/)   { state = 3 }   # the next heading arrived first
        next
      }
      state == 2 {
        if ($0 ~ /^```/) { state = 3; next }
        print
        if ($0 ~ /[^[:space:]]/) nonblank = 1
        next
      }
      { next }
      END {
        if (state == 2)  exit 4   # fence opened and never closed
        if (!sawfence)   exit 1
        if (!nonblank)   exit 3
        exit 0
      }
    '
  st=("${PIPESTATUS[@]}")
  [ "${st[0]}" -eq 0 ] || return "${st[0]}"
  case "${st[1]}" in
    0) return 0 ;;
    1) echo "[$version]: no fenced block under \"### Store notes\"" >&2
       return $CHANGELOG_NO_STORE_BLOCK ;;
    3) echo "[$version]: the store-notes block is empty" >&2
       return $CHANGELOG_EMPTY_STORE_BLOCK ;;
    4) echo "[$version]: the store-notes fence is never closed" >&2
       return $CHANGELOG_MALFORMED ;;
    *) return 1 ;;
  esac
}

# Streams VER's `- ` entries from the category subsections (never from the store
# block). Used to assert the tier is non-empty and to feed the heuristic below;
# the entries themselves are never published anywhere.
changelog_entries() {
  local version="$1"
  local -a st

  _changelog_section_lines "$version" |
    awk '
      { if ($0 ~ /^```/) { fence = 1 - fence; next } }
      fence { next }
      /^### / { cat = substr($0, 5); next }
      cat != "" && cat != "Store notes" && /^- / { print; n++ }
      END { exit(n ? 0 : 1) }
    '
  st=("${PIPESTATUS[@]}")
  [ "${st[0]}" -eq 0 ] || return "${st[0]}"
  [ "${st[1]}" -eq 0 ] || return $CHANGELOG_NO_ENTRIES
}

# Checks FILE against Play's limit.
#
# Counts BYTES, not characters, and that is deliberate. Bytes >= characters
# always, so a byte guard can only ever reject text that would have fit -- never
# admit text that would not. The alternative, `wc -m`, needs a UTF-8 locale
# present on both a macOS and an Ubuntu runner, which is not something worth
# betting a release on. The cost is a little headroom: each `•` is 3 bytes.
changelog_check_limit() {
  local file="$1" bytes
  # Without this, an unreadable path makes `wc -c` print nothing, the comparison
  # below errors, and the function returns 0 -- reporting a release as within the
  # limit precisely when nothing was measured.
  [ -r "$file" ] || { echo "changelog_check_limit: cannot read '$file'" >&2; return 1; }
  bytes=$(wc -c < "$file")
  bytes=${bytes// /}

  if [ "$bytes" -gt "$CHANGELOG_STORE_LIMIT_BYTES" ]; then
    echo "store notes are $bytes bytes; Google Play's limit is $CHANGELOG_STORE_LIMIT_BYTES." >&2
    echo "Shorten the \"### Store notes\" block -- the detail belongs in the changelog below it." >&2
    return $CHANGELOG_TOO_LONG
  fi
  echo "store notes: $bytes/$CHANGELOG_STORE_LIMIT_BYTES bytes"
}

# Warns about entries that look like changes a user cannot observe. WARNING ONLY,
# on purpose: "Fixed a crash when upgrading from 0.0.6" is a perfectly good entry,
# and a check that fails on it would be worked around rather than heeded. The
# rule itself is editorial and lives in CHANGELOG.md's header; this catches the
# cheap half of it for free.
#
# Scoped to VER's section, not the whole file: the header comment states the rule
# using the very words the heuristic looks for, so a file-wide grep would warn on
# every single run and teach everyone to ignore it.
changelog_warn_non_observable() {
  local version="$1" hits
  hits=$(_changelog_section_lines "$version" |
    grep -nEi '^[[:space:]]*([-*]|•)[[:space:]]*(bump(ed)?|upgrade[d]?|refactor(ed)?)\b|dependenc' || true)
  [ -n "$hits" ] || return 0

  echo "warning: these lines look like changes a user cannot observe --" >&2
  echo "         check them against the rule in CHANGELOG.md's header:" >&2
  printf '%s\n' "$hits" | sed 's/^/           /' >&2
  return 0
}
