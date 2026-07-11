#!/usr/bin/env bash
set -euo pipefail

# Derives the version + Android versionCode from the v* tag being built. Used by
# publish.yml, which is triggered by the tag push that release.yml makes, so the
# tag is the single source of truth for what a build calls itself -- rebuilding a
# tag always reproduces the same version and versionCode.

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=version_lib.sh
source "$(dirname "$0")/version_lib.sh"

TAG="${RELEASE_TAG:?RELEASE_TAG not set}"

case "$TAG" in
  v*) ;;
  *) echo "Not a release tag: '$TAG' (expected a v* tag such as v1.2.3)" >&2; exit 1 ;;
esac

VERSION="${TAG#v}"
parse_semver "$VERSION"
VERSION_CODE=$(version_code "$MAJOR" "$MINOR" "$PATCH")

{
  echo "version=$VERSION"
  echo "tag=$TAG"
  echo "version_code=$VERSION_CODE"
} >> "${GITHUB_OUTPUT:-/dev/null}"

echo "Building tag: $TAG"
echo "Version: $VERSION"
echo "Version code: $VERSION_CODE"
