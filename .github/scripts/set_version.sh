#!/usr/bin/env bash
set -euo pipefail

# Stamps NEW_VERSION / NEW_VERSION_CODE into both platforms:
#   android-app/module.yaml                  -> settings.android.versionName / .versionCode
#   ios-app/module.xcodeproj/project.pbxproj -> MARKETING_VERSION / CURRENT_PROJECT_VERSION
#
# The iOS substitution is global on purpose: it hits all four XCBuildConfiguration
# blocks (app + AzulaShare, Debug + Release), which must all carry the same value --
# Apple rejects an extension whose version differs from its host. Both Info.plists
# read the settings through $(...), so this is the single source of truth.
#
# Run from anywhere in the repo. sed -i.bak (suffix attached, no space) is the one
# spelling that works on both GNU sed (ubuntu job) and BSD sed (macOS job).

new_version="${NEW_VERSION:?NEW_VERSION not set}"
new_version_code="${NEW_VERSION_CODE:?NEW_VERSION_CODE not set}"

cd "$(git rev-parse --show-toplevel)"

android_module="android-app/module.yaml"
ios_pbxproj="ios-app/module.xcodeproj/project.pbxproj"

for f in "$android_module" "$ios_pbxproj"; do
  [ -f "$f" ] || { echo "$f not found!" >&2; exit 1; }
done

# Fail loudly if a refactor renamed the keys out from under us, rather than
# silently shipping the committed placeholder version.
grep -q 'versionCode:' "$android_module" || { echo "no versionCode: in $android_module" >&2; exit 1; }
grep -q 'versionName:' "$android_module" || { echo "no versionName: in $android_module" >&2; exit 1; }
grep -q 'MARKETING_VERSION' "$ios_pbxproj" || { echo "no MARKETING_VERSION in $ios_pbxproj" >&2; exit 1; }
grep -q 'CURRENT_PROJECT_VERSION' "$ios_pbxproj" || { echo "no CURRENT_PROJECT_VERSION in $ios_pbxproj" >&2; exit 1; }

sed -i.bak "s/versionCode: .*/versionCode: $new_version_code # Overwritten by CI/" "$android_module"
sed -i.bak "s/versionName: .*/versionName: $new_version # Overwritten by CI/" "$android_module"

sed -i.bak "s/MARKETING_VERSION = [^;]*;/MARKETING_VERSION = $new_version;/g" "$ios_pbxproj"
sed -i.bak "s/CURRENT_PROJECT_VERSION = [^;]*;/CURRENT_PROJECT_VERSION = $new_version_code;/g" "$ios_pbxproj"

rm -f "$android_module.bak" "$ios_pbxproj.bak"

echo "Stamped version $new_version (code $new_version_code):"
grep -nE 'versionCode:|versionName:' "$android_module"
grep -cE 'MARKETING_VERSION = |CURRENT_PROJECT_VERSION = ' "$ios_pbxproj" \
  | xargs echo "  pbxproj version lines updated:"
