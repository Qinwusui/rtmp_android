#!/bin/sh

# This project is constrained to the existing Gradle toolchain; never download a distribution.
rtmp_gradle_cached=false
for rtmp_gradle_bin in "${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-9.5.0-bin"/*/gradle-9.5.0/bin/gradle; do
    if [ -x "$rtmp_gradle_bin" ]; then rtmp_gradle_cached=true; break; fi
done
if [ "$rtmp_gradle_cached" != true ]; then
    echo 'Gradle 9.5.0 is not available in the local cache. Automatic toolchain installation is disabled.' >&2
    exit 1
fi

# Minimal, versioned Gradle Wrapper launcher. The wrapper JAR and distribution
# URL provide the reproducible Gradle version; this script deliberately has no
# machine-specific SDK or proxy configuration.
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1

# macOS ships a `/usr/bin/java` shim that exits without a runtime when
# JAVA_HOME is unset, even if Homebrew's JDK 21 is installed.  Select an
# existing JDK 21 for a direct wrapper invocation while respecting an explicit
# JAVA_HOME supplied by CI or the caller.
if [ -z "${JAVA_HOME:-}" ]; then
  detected_java_home=""
  if [ "$(uname -s 2>/dev/null || true)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
    detected_java_home=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  fi
  if [ ! -x "$detected_java_home/bin/java" ] && [ -x /usr/local/opt/openjdk@21/bin/java ]; then
    detected_java_home=/usr/local/opt/openjdk@21
  fi
  if [ ! -x "$detected_java_home/bin/java" ] && [ -x /opt/homebrew/opt/openjdk@21/bin/java ]; then
    detected_java_home=/opt/homebrew/opt/openjdk@21
  fi
  if [ -x "$detected_java_home/bin/java" ]; then
    JAVA_HOME=$detected_java_home
    export JAVA_HOME
  fi
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_cmd="$JAVA_HOME/bin/java"
else
  java_cmd=java
fi

# Android Gradle Plugin needs an SDK for KMP Android host tests.  Mirror the
# macOS-standard discovery used by the repository Make targets so
# `app/gradlew` is usable from a fresh terminal without a generated,
# machine-specific local.properties file. Explicit caller or CI settings win.
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  detected_android_sdk=""
  if [ -d "${HOME}/Library/Android/sdk" ]; then
    detected_android_sdk="${HOME}/Library/Android/sdk"
  fi
  if [ -n "$detected_android_sdk" ]; then
    ANDROID_HOME=$detected_android_sdk
    ANDROID_SDK_ROOT=$detected_android_sdk
    export ANDROID_HOME ANDROID_SDK_ROOT
  fi
fi
exec "$java_cmd" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
