#!/bin/sh
# Runs on the build host inside the synced source tree: unit tests, then the release APK.
# Needs ANDROID_HOME (platform 36, build-tools 35.0.0) and JDK 17+. Set GAMEPERFCONFIG to a
# copy of the device's /system/etc/gameperfconfig.xml to also test against the vendor file.
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME}"
./gradlew --no-daemon :app:testReleaseUnitTest :app:assembleRelease
ls -l app/build/outputs/apk/release/*.apk
