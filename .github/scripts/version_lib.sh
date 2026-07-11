#!/usr/bin/env bash
# Shared version math for the release (tag-cutting) and publish (tag-building)
# workflows. Sourced, not executed.

# Splits a semver triple into MAJOR / MINOR / PATCH, rejecting anything that
# isn't three plain integers (a tag like v1.2 or v1.2.3-rc1 would otherwise
# silently produce a garbage version code).
parse_semver() {
  local version="$1"
  if ! [[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "Not a MAJOR.MINOR.PATCH version: '$version'" >&2
    return 1
  fi
  MAJOR="${BASH_REMATCH[1]}"
  MINOR="${BASH_REMATCH[2]}"
  PATCH="${BASH_REMATCH[3]}"
}

# The Android versionCode. Play requires it to increase on every upload, and the
# platform builds derive it from the tag they were triggered by, so it has to be a
# pure function of the tag -- no "look at what's already published and add one".
#
# That only stays monotonic while minor and patch are below 100: 1.0.100 and 1.1.0
# would both give 10100, and after that a *higher* version could produce a *lower*
# code, which Play would reject. Rather than silently paper over it, refuse to cut
# the release; widening the multipliers later is safe (every code grows), narrowing
# them is not. See azula-docs/docs/release.md.
version_code() {
  local major="$1" minor="$2" patch="$3"
  if [ "$minor" -ge 100 ] || [ "$patch" -ge 100 ]; then
    echo "minor and patch must stay below 100 (got $major.$minor.$patch): the" >&2
    echo "versionCode formula major*10000 + minor*100 + patch stops increasing" >&2
    echo "monotonically above that. Bump the next level up, or widen the formula." >&2
    return 1
  fi
  echo $((major * 10000 + minor * 100 + patch))
}
