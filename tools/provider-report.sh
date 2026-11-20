#!/usr/bin/env bash
# Shows how BoschTV reads an IPTV provider's catalog: which categories it uses, as what language,
# genre and streaming service, and which it leaves out. Use it when adding support for a provider.
#
#   tools/provider-report.sh                 asks for server, username, password and language
#
# Only the three category lists are fetched (no streams). The login is passed to the local test run
# and never written to disk; the report is written to app/build/provider-report.txt.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

read -r -p "Server (e.g. http://example.com:8080): " SERVER
read -r -p "Username: " USERNAME
read -r -s -p "Password: " PASSWORD; echo
read -r -p "Home language [DUTCH, FLEMISH, ENGLISH, GERMAN, FRENCH, SPANISH, ITALIAN, PORTUGUESE, POLISH, TURKISH, ARABIC] (DUTCH): " LANGUAGE

if ! ./gradlew :app:testDebugUnitTest --tests 'nl.boschtv.tv.ProviderReport' -q \
    -Pxtream.server="$SERVER" -Pxtream.user="$USERNAME" -Pxtream.pass="$PASSWORD" -Pxtream.language="${LANGUAGE:-DUTCH}" >/dev/null 2>&1; then
    # Show the reason (expired account, wrong login, unreachable server) instead of Gradle's output.
    sed -n 's/.*message="\([^"]*\)".*/\1/p' app/build/test-results/testDebugUnitTest/TEST-nl.boschtv.tv.ProviderReport.xml | head -1 | sed 's/&quot;/"/g' >&2
    exit 1
fi
echo
head -60 app/build/provider-report.txt
echo "…"
echo "Full report: app/build/provider-report.txt"
