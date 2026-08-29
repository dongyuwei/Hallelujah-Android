#!/usr/bin/env bash
# Build the debug APK (installable, signed with the debug key).
set -euo pipefail
cd "$(dirname "$0")"

./gradlew assembleDebug

echo
echo "Debug APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
