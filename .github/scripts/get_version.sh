#!/usr/bin/env bash
set -euo pipefail

# Cuts the next version: reads the latest v* tag and bumps it by BUMP_TYPE
# (major|minor|patch). Used by release.yml, which pushes the resulting tag; the
# tag push is what triggers publish.yml to actually build and ship it.
#
# The version that's live is whatever the newest tag says, so each release is
# always exactly one increment ahead of it.

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=version_lib.sh
source "$(dirname "$0")/version_lib.sh"

git fetch --tags --force

LAST_TAG=$(git tag --list --sort=-v:refname 'v*' | head -n1)
if [ -z "$LAST_TAG" ]; then
  LAST_TAG="v0.0.0"
  echo "No existing tags; starting from $LAST_TAG"
fi
echo "Latest tag: $LAST_TAG"

parse_semver "${LAST_TAG#v}"

BUMP_TYPE=${BUMP_TYPE:-patch}
case "$BUMP_TYPE" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
  *) echo "Unknown BUMP_TYPE: '$BUMP_TYPE' (expected major, minor, or patch)" >&2; exit 1 ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
NEW_TAG="v$NEW_VERSION"
NEW_VERSION_CODE=$(version_code "$MAJOR" "$MINOR" "$PATCH")

if git rev-parse -q --verify "refs/tags/$NEW_TAG" >/dev/null; then
  echo "Tag $NEW_TAG already exists." >&2
  exit 1
fi

{
  echo "version=$NEW_VERSION"
  echo "tag=$NEW_TAG"
  echo "version_code=$NEW_VERSION_CODE"
} >> "${GITHUB_OUTPUT:-/dev/null}"

echo "New version: $NEW_VERSION"
echo "New tag: $NEW_TAG"
echo "New version code: $NEW_VERSION_CODE"
