#!/usr/bin/env bash
# Regenerate every azula brand-icon asset from the master SVGs in this directory.
#
# Sources (this dir):
#   icon.master.svg      rounded, transparent corners — desktop / favicon / .ico / .icns
#   icon.square.svg      full-bleed opaque square      — iOS, Android background
#   icon.foreground.svg  mark only, inset, transparent — Android adaptive foreground
#   favicon.src.svg      compact mark                  — web favicon (baked to paths)
#
# The `-mock` apps get an INVERTED render of these same masters (see `invert`
# below): light ground, same green/magenta mark, so the mock build is instantly
# tellable apart from the real one in a launcher or dock. The masters are never
# forked — inverting is a palette substitution, so the geometry can't drift.
#
# Requires: inkscape, magick (ImageMagick), sips + iconutil (macOS). Font:
# JetBrains Mono must be installed (it is, under ~/Library/Fonts). Outputs land
# in ./out and are copied into the repo asset locations.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$here/../.." && pwd)"          # azula-app
out="$here/out"
ink() { inkscape "$1" --export-type=png --export-filename="$2" -w "$3" -h "$3" >/dev/null 2>&1; }

# Write an inverted-ground copy of a master SVG to $out/inv/ and echo its path.
# Only the *ground* flips — the brand mark keeps its green #52c98a and magenta
# #ff2d9b. Three things have to move with the background or the render breaks:
# the hairline edge (invisible on white), the scanline overlay (white-on-white),
# and the two glow floods (a 0.6-opacity bloom over white reads as mud, so they
# come down to a hint of halo).
invert() {
  local src="$1" dst="$out/inv/$(basename "$1")"
  sed -e 's/#070709/#f8f8f6/g' \
      -e 's/#23232e/#c9c9d4/g' \
      -e 's/fill="#ffffff" opacity="0.018"/fill="#0b0b12" opacity="0.045"/' \
      -e 's/flood-opacity="0.6"/flood-opacity="0.28"/g' \
      -e 's/flood-opacity="0.45"/flood-opacity="0.22"/g' \
      "$src" > "$dst"
  echo "$dst"
}

rm -rf "$out"; mkdir -p "$out"/{ios,jvm,web,android,font,macos.iconset,inv}
mkdir -p "$out"/inv/{ios,jvm,android}

echo "› rendering master PNGs"
for s in 1024 512 256 128; do ink "$here/icon.master.svg" "$out/master-$s.png" "$s"; done

# ---- iOS: single 1024 universal, opaque, no alpha ----
ink "$here/icon.square.svg" "$out/ios/icon-1024.png" 1024
magick "$out/ios/icon-1024.png" -background "#070709" -alpha remove -alpha off "$out/ios/icon-1024.png"

# ---- Desktop window icon (composeResources drawable) ----
ink "$here/icon.master.svg" "$out/jvm/app_icon.png" 1024

# ---- macOS .icns (best-effort) ----
for s in 16 32 64 128 256 512; do
  ink "$here/icon.master.svg" "$out/macos.iconset/icon_${s}x${s}.png" "$s"
  d=$((s*2)); ink "$here/icon.master.svg" "$out/macos.iconset/icon_${s}x${s}@2x.png" "$d"
done
ink "$here/icon.master.svg" "$out/macos.iconset/icon_512x512@2x.png" 1024
iconutil -c icns "$out/macos.iconset" -o "$out/jvm/azula.icns" 2>/dev/null || echo "  (iconutil skipped)"

# ---- Windows .ico ----
magick "$out/master-256.png" "$out/master-128.png" \
  \( "$out/master-256.png" -resize 64x64 \) \( "$out/master-256.png" -resize 48x48 \) \
  \( "$out/master-256.png" -resize 32x32 \) \( "$out/master-256.png" -resize 16x16 \) \
  "$out/jvm/azula.ico"

# ---- Web favicon: bake glyphs to paths so the browser needs no font ----
inkscape "$here/favicon.src.svg" --export-text-to-path --export-plain-svg \
  --export-filename="$out/web/favicon.svg" >/dev/null 2>&1
ink "$here/favicon.src.svg" "$out/web/apple-touch-icon.png" 180
magick "$out/web/apple-touch-icon.png" -background "#070709" -alpha remove "$out/web/apple-touch-icon.png"
for s in 32 16; do ink "$here/favicon.src.svg" "$out/web/favicon-$s.png" "$s"; done

# ---- Android adaptive: foreground densities (108dp grid) + legacy mipmaps ----
# (density:foreground-px:legacy-px — plain arrays for bash 3.2 portability)
for row in mdpi:108:48 hdpi:162:72 xhdpi:216:96 xxhdpi:324:144 xxxhdpi:432:192; do
  d="${row%%:*}"; rest="${row#*:}"; fg="${rest%%:*}"; leg="${rest##*:}"
  mkdir -p "$out/android/mipmap-$d"
  ink "$here/icon.foreground.svg" "$out/android/mipmap-$d/ic_launcher_foreground.png" "$fg"
  ink "$here/icon.master.svg"     "$out/android/mipmap-$d/ic_launcher.png"            "$leg"
  cp "$out/android/mipmap-$d/ic_launcher.png" "$out/android/mipmap-$d/ic_launcher_round.png"
done

# ---- Android notification small icon: white ›a silhouette, density PNGs ----
for row in mdpi:24 hdpi:36 xhdpi:48 xxhdpi:72 xxxhdpi:96; do
  d="${row%%:*}"; px="${row##*:}"
  mkdir -p "$out/android/drawable-$d"
  ink "$here/icon.notification.svg" "$out/android/drawable-$d/ic_stat_azula.png" "$px"
done

# ============================================================================
# Inverted renders for the `-mock` apps (light ground, same mark)
# ============================================================================
echo "› rendering inverted (mock) icons"
inv_master="$(invert "$here/icon.master.svg")"
inv_square="$(invert "$here/icon.square.svg")"
inv_fg="$(invert "$here/icon.foreground.svg")"

# iOS mock: opaque 1024, flattened onto the light ground (not the dark one).
ink "$inv_square" "$out/inv/ios/icon-1024.png" 1024
magick "$out/inv/ios/icon-1024.png" -background "#f8f8f6" -alpha remove -alpha off "$out/inv/ios/icon-1024.png"

# Desktop mock: the rounded master, for jvm-app-mock's window/dock icon.
ink "$inv_master" "$out/inv/jvm/app_icon_mock.png" 1024

# Android mock: adaptive foreground + legacy mipmaps, same density grid as the
# real pass. No ic_stat_azula — the mock build has no notifier to draw one.
for row in mdpi:108:48 hdpi:162:72 xhdpi:216:96 xxhdpi:324:144 xxxhdpi:432:192; do
  d="${row%%:*}"; rest="${row#*:}"; fg="${rest%%:*}"; leg="${rest##*:}"
  mkdir -p "$out/inv/android/mipmap-$d"
  ink "$inv_fg"     "$out/inv/android/mipmap-$d/ic_launcher_foreground.png" "$fg"
  ink "$inv_master" "$out/inv/android/mipmap-$d/ic_launcher.png"            "$leg"
  cp "$out/inv/android/mipmap-$d/ic_launcher.png" "$out/inv/android/mipmap-$d/ic_launcher_round.png"
done

# ---- Fonts: static JetBrains Mono (Regular + Bold) for composeResources ----
# Prefer the on-disk static instances (zero network, OFL-licensed).
src_font="$HOME/Library/Fonts"
if [[ -f "$src_font/JetBrainsMonoNL-Regular.ttf" && -f "$src_font/JetBrainsMonoNL-Bold.ttf" ]]; then
  cp "$src_font/JetBrainsMonoNL-Regular.ttf" "$out/font/jetbrainsmono_regular.ttf"
  cp "$src_font/JetBrainsMonoNL-Bold.ttf"    "$out/font/jetbrainsmono_bold.ttf"
else
  echo "  (JetBrains Mono static not found in ~/Library/Fonts — fetch the OFL release manually)"
fi

# ============================================================================
# Copy into repo asset locations
# ============================================================================
echo "› installing into repo"

# Desktop window icon (shared/) and the bundled fonts (theme/ owns those — it
# declares the FontFamily, so its composeResources is where they have to land).
mkdir -p "$app/shared/composeResources/drawable" "$app/theme/composeResources/font"
cp "$out/jvm/app_icon.png" "$app/shared/composeResources/drawable/app_icon.png"
[[ -f "$out/font/jetbrainsmono_regular.ttf" ]] && cp "$out/font/jetbrainsmono_regular.ttf" "$app/theme/composeResources/font/"
[[ -f "$out/font/jetbrainsmono_bold.ttf" ]]    && cp "$out/font/jetbrainsmono_bold.ttf"    "$app/theme/composeResources/font/"

# Android res
res="$app/android-app/res"
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$res/mipmap-$d" "$res/drawable-$d"
  cp "$out/android/mipmap-$d/"*.png "$res/mipmap-$d/"
  cp "$out/android/drawable-$d/"*.png "$res/drawable-$d/"
done

# iOS asset catalog
ios="$app/ios-app/src/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$ios"
cp "$out/ios/icon-1024.png" "$ios/icon-1024.png"

# ---- the `-mock` apps: the inverted renders ----
mres="$app/android-app-mock/res"
for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$mres/mipmap-$d"
  cp "$out/inv/android/mipmap-$d/"*.png "$mres/mipmap-$d/"
done

mios="$app/ios-app-mock/src/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$mios"
cp "$out/inv/ios/icon-1024.png" "$mios/icon-1024.png"

# mock-support owns the desktop mock icon (rather than shared/) so the inverted
# asset never ships inside the real app.
mkdir -p "$app/mock-support/composeResources/drawable"
cp "$out/inv/jvm/app_icon_mock.png" "$app/mock-support/composeResources/drawable/app_icon_mock.png"

# Site — inline the favicon SVG + apple-touch PNG into the Worker (no asset
# binding; the Worker serves them from routes). Regenerates src/icon.ts.
site="$(cd "$app/../azula-site" 2>/dev/null && pwd || true)"
if [[ -n "$site" && -d "$site/src" ]]; then
  SVG="$(cat "$out/web/favicon.svg")"
  B64="$(base64 -i "$out/web/apple-touch-icon.png" | tr -d '\n')"
  {
    echo "// Brand icons for the site, generated by azula-app/design/icon/generate.sh."
    echo "// FAVICON_SVG has its glyphs baked to paths; APPLE_TOUCH_PNG_B64 is a 180px PNG."
    echo "// Served by the Worker at /favicon.svg and /apple-touch-icon.png."
    echo
    echo "export const FAVICON_SVG = \`${SVG}\`;"
    echo
    echo "export const APPLE_TOUCH_PNG_B64 ="
    echo "  \"${B64}\";"
    echo
    echo "/** Decode a base64 string to bytes (Worker runtime has atob). */"
    echo "export function b64ToBytes(b64: string): Uint8Array {"
    echo "  const bin = atob(b64);"
    echo "  const out = new Uint8Array(bin.length);"
    echo "  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);"
    echo "  return out;"
    echo "}"
  } > "$site/src/icon.ts"
  echo "  wrote $site/src/icon.ts"
fi

echo "✓ done. Assets in $out and installed into the repos."
