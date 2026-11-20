#!/usr/bin/env bash
# Builds a new version of BoschTV and publishes it, so installed copies offer the update.
#
#   tools/release.sh 2.3.0 "Tv-gids met tijdlijn"     version name + release notes (shown in the app)
#   tools/release.sh 2.3.0 "…" --dry-run              build and write dist/, publish nothing
#   tools/release.sh 2.3.0 "…" --own-logos            also put your own logo files (app/src/main/assets/icons)
#                                                     in the public app; by default releases use TMDB's logos only
#
# Publishes to the GitHub repo in RELEASE_REPO (default boschtv/boschtv) as release
# v<version>, with two files: boschtv.apk and update.json. The app reads
#   https://github.com/<repo>/releases/latest/download/update.json
# so local.properties needs:  update.url=https://github.com/<repo>/releases/latest/download/update.json
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${1:?Usage: tools/release.sh <version, e.g. 2.3.0> <release notes> [--dry-run]}"
NOTES="${2:-}"
DRY=""; OWN=""
for a in "${@:3}"; do case "$a" in --dry-run) DRY="$a" ;; --own-logos) OWN="$a" ;; *) echo "Unknown option: $a" >&2; exit 1 ;; esac; done
REPO="${RELEASE_REPO:-boschtv/boschtv}"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME PATH="$JAVA_HOME/bin:$PATH"

grep -q '^update.url=' local.properties 2>/dev/null || { echo "local.properties has no update.url; the released app would never look for updates." >&2; exit 1; }

# Every release gets a higher versionCode: that is what the app compares.
CODE=$(( $(sed -n 's/^versionCode=//p' version.properties) + 1 ))
printf 'versionCode=%s\nversionName=%s\n' "$CODE" "$NAME" > version.properties
# Your own logo files are for your own builds; a public release shows TMDB's logos unless asked otherwise.
ICONS=app/src/main/assets/icons
if [ -z "$OWN" ] && [ -d "$ICONS" ]; then
    # Set aside outside the assets folder (anything inside it would still be packed into the app).
    mkdir -p dist && rm -rf dist/.icons-aside && mv "$ICONS" dist/.icons-aside
    trap 'mv dist/.icons-aside "$ICONS"' EXIT
    echo "Leaving your own logos out of this public build (use --own-logos to include them)."
fi
# Gradle keeps a merged copy of the assets; clear it so the build reflects exactly what is in the folder.
rm -rf app/build/intermediates/assets app/build/intermediates/merged_assets app/build/intermediates/compressed_assets
echo "Building $NAME (versionCode $CODE)…"
./gradlew :app:testDebugUnitTest :app:assembleRelease -q

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/boschtv.apk
APK_URL="https://github.com/$REPO/releases/download/v$NAME/boschtv.apk"
python3 - "$CODE" "$NAME" "$APK_URL" "$NOTES" > dist/update.json <<'PY'
import json, sys
code, name, apk, notes = sys.argv[1:]
print(json.dumps({"versionCode": int(code), "versionName": name, "apk": apk, "notes": notes or None}, ensure_ascii=False, indent=2))
PY
echo "dist/ ready: boschtv.apk ($(du -h dist/boschtv.apk | cut -f1)), update.json"

if [ "$DRY" = "--dry-run" ]; then echo "Dry run: nothing published."; exit 0; fi
gh release create "v$NAME" dist/boschtv.apk dist/update.json --repo "$REPO" --title "BoschTV $NAME" --notes "${NOTES:-BoschTV $NAME}"
echo "Published. Installed copies offer $NAME within a few hours, or right away via Settings > Check for updates."
