#!/usr/bin/env bash
# Installs the production build of boschtv on a Google TV / Android TV over wifi.
#
#   tools/install-tv.sh            install (builds first if there is no APK yet)
#   tools/install-tv.sh --build    always rebuild before installing
#   tools/install-tv.sh --forget   forget the saved TV and ask everything again
#
# First run asks for the TV's IP, and (once) the pairing port + code. After that it
# remembers the TV and finds the debugging port by itself when it can.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

APK="app/build/outputs/apk/release/app-release.apk"
PACKAGE="nl.boschtv.tv"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/boschtv"
CONFIG="$CONFIG_DIR/tv"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

ask() { # ask "Question" "default" -> answer on stdout
    local answer prompt="$1" default="${2:-}"
    if [ -n "$default" ]; then read -r -p "$prompt [$default]: " answer; else read -r -p "$prompt: " answer; fi
    printf '%s' "${answer:-$default}"
}

command -v adb >/dev/null || die "adb not found. Install the Android platform-tools first."

BUILD=0
for arg in "$@"; do
    case "$arg" in
        --build) BUILD=1 ;;
        --forget) rm -f "$CONFIG"; note "Saved TV forgotten." ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "Unknown option: $arg" ;;
    esac
done

TV_IP=""; TV_PORT=""
# shellcheck disable=SC1090
[ -f "$CONFIG" ] && . "$CONFIG"

save() {
    mkdir -p "$CONFIG_DIR"
    printf 'TV_IP=%s\nTV_PORT=%s\n' "$TV_IP" "$TV_PORT" > "$CONFIG"
}

# True when ip:port is connected and authorised.
online() { [ "$(adb -s "$1" get-state 2>/dev/null || true)" = "device" ]; }

try_connect() { # try_connect ip port
    adb connect "$1:$2" >/dev/null 2>&1 || true
    online "$1:$2"
}

# The debugging port changes whenever wireless debugging is switched off and on. The TV
# announces the current one on the network, so look it up instead of asking when possible.
discover_port() { # discover_port ip -> port on stdout (empty when not found)
    adb mdns services 2>/dev/null | awk -v ip="$1" '
        /_adb-tls-connect/ { n = split($NF, a, ":"); if (a[1] == ip) { print a[n]; exit } }'
}

# ---------------------------------------------------------------- 1. which TV
say "1. Your TV"
note "On the TV: Settings > System > Developer options > Wireless debugging (switch it on)."
note "No Developer options? Settings > System > About, press 'Android TV OS build' 7 times."
TV_IP="$(ask "IP address of the TV" "$TV_IP")"
[ -n "$TV_IP" ] || die "An IP address is needed."

# ---------------------------------------------------------------- 2. connect
say "2. Connecting"
adb start-server >/dev/null 2>&1 || true
CONNECTED=0

if [ -n "$TV_PORT" ] && try_connect "$TV_IP" "$TV_PORT"; then
    CONNECTED=1; note "Connected on the saved port $TV_PORT."
fi

if [ "$CONNECTED" = 0 ]; then
    FOUND="$(discover_port "$TV_IP")"
    if [ -n "$FOUND" ] && try_connect "$TV_IP" "$FOUND"; then
        TV_PORT="$FOUND"; CONNECTED=1; note "Found the TV on port $TV_PORT."
    fi
fi

if [ "$CONNECTED" = 0 ]; then
    note "Could not connect yet."
    PAIRED="$(ask "Has this computer been paired with the TV before? (y/n)" "n")"
    if [ "$PAIRED" != "y" ] && [ "$PAIRED" != "Y" ]; then
        say "   Pairing (only needed once)"
        note "On the TV, in Wireless debugging, choose 'Pair device with pairing code'."
        note "It shows an IP address with a port, and a 6-digit code."
        PAIR_PORT="$(ask "Pairing port (the number after the colon)")"
        PAIR_CODE="$(ask "Pairing code")"
        [ -n "$PAIR_PORT" ] && [ -n "$PAIR_CODE" ] || die "Pairing port and code are both needed."
        adb pair "$TV_IP:$PAIR_PORT" "$PAIR_CODE" || die "Pairing failed. Check the port and code; they change each time the dialog opens."
        sleep 1
        FOUND="$(discover_port "$TV_IP")"
        if [ -n "$FOUND" ] && try_connect "$TV_IP" "$FOUND"; then
            TV_PORT="$FOUND"; CONNECTED=1; note "Paired and connected on port $TV_PORT."
        fi
    fi
fi

if [ "$CONNECTED" = 0 ]; then
    note "On the Wireless debugging screen itself (not the pairing dialog) the TV shows"
    note "'IP address & Port'. That port is the debugging port."
    TV_PORT="$(ask "Debugging port" "$TV_PORT")"
    [ -n "$TV_PORT" ] || die "A debugging port is needed."
    try_connect "$TV_IP" "$TV_PORT" || die "Still cannot connect to $TV_IP:$TV_PORT. Is wireless debugging on, and are both devices on the same wifi?"
    CONNECTED=1; note "Connected."
fi

save
TARGET="$TV_IP:$TV_PORT"
MODEL="$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
note "TV: ${MODEL:-unknown} at $TARGET (remembered for next time)"

# ---------------------------------------------------------------- 3. build
if [ "$BUILD" = 1 ] || [ ! -f "$APK" ]; then
    say "3. Building the production version"
    ./gradlew :app:assembleRelease -q
else
    say "3. Using the existing build"
    note "$(date -r "$APK" '+Built %d %b %Y %H:%M'). Run with --build to rebuild first."
fi
[ -f "$APK" ] || die "No APK at $APK."

# ---------------------------------------------------------------- 4. install
say "4. Installing"
adb -s "$TARGET" install -r "$APK"

say "5. Optimising for speed"
note "Compiles the app ahead of time so it is fast from the first launch (takes a moment)."
adb -s "$TARGET" shell cmd package compile -m speed -f "$PACKAGE" >/dev/null 2>&1 || note "Skipped; the TV will optimise it by itself later."

say "6. Starting boschtv"
adb -s "$TARGET" shell am start -n "$PACKAGE/.MainActivity" >/dev/null 2>&1 || true

VERSION="$(adb -s "$TARGET" shell dumpsys package "$PACKAGE" 2>/dev/null | awk -F= '/versionName/ { print $2; exit }' | tr -d '\r')"
say "Done. boschtv ${VERSION:-} is on your TV."
