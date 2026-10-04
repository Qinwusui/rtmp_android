#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${RTMP_JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
# Requires the specified, already cached Gradle distribution. Never bootstraps a toolchain.
if ! test -d "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-9.5.0-bin"; then
    echo 'Gradle 9.5.0 is not cached; toolchain installation is disabled.' >&2
    exit 1
fi
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :capture:testDebugUnitTest :auth-server:test :auth-server:installDist :app:lint :capture:lint --console=plain
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/rtmpcapture-debug.apk
cp app/build/outputs/apk/release/app-release.apk artifacts/rtmpcapture-release.apk
cp app/build/outputs/mapping/release/mapping.txt artifacts/release-mapping.txt
