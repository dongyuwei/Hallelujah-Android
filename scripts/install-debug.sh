#!/bin/sh
# Installs the debug APK on a connected device and force-AOT-compiles the
# package: debuggable apps are skipped by Android's background dexopt, and
# the rime engine is unusable without compiled code. Run after every
# (re)install when testing debug builds.
set -e
ADB="${ADB:-adb}"
PKG=rkr.tinykeyboard.inputmethod
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || APK="app-debug.apk"
"$ADB" install -r "$APK"
"$ADB" shell am force-stop "$PKG"
"$ADB" shell cmd package compile -m speed -f "$PKG"
echo "installed + AOT-compiled $PKG"
