#!/usr/bin/env bash
# Build the release APK (minified with R8, unsigned — sign it before distributing).
set -euo pipefail
cd "$(dirname "$0")"

./gradlew assembleRelease

echo
echo "Release APK (unsigned): $(pwd)/app/build/outputs/apk/release/app-release-unsigned.apk"
