#!/usr/bin/env bash
# Helper to enforce and use a single build variant + ABI for builds, installs and tests.
# Created by assistant per user request. Place in repo and source it from your shell.

set -euo pipefail

# Default values (can be changed by calling set_variant)
BUILD_VARIANT="fullpermDebug"
ARCH="armeabi-v7a"
DEVICE_ID="30870044490006E"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT"

# Canonical absolute APK paths (recorded from user session). These are the
# canonical locations to use for installs/tests for the enforced variant/arch.
# Do NOT search elsewhere unless these files are missing.
CANONICAL_APP_APK="$PROJECT_ROOT/abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-armeabi-v7a-debug.apk"
CANONICAL_TEST_APK="$PROJECT_ROOT/abhaya-sensor-android/app/build/outputs/apk/androidTest/fullperm/debug/app-fullperm-debug-androidTest.apk"

export BUILD_VARIANT ARCH DEVICE_ID PROJECT_ROOT LOG_DIR

set_variant() {
  if [ "$#" -lt 2 ]; then
    echo "Usage: set_variant <buildVariant> <arch>"
    return 1
  fi
  BUILD_VARIANT="$1"
  ARCH="$2"
  export BUILD_VARIANT ARCH
  echo "Variant set: BUILD_VARIANT=$BUILD_VARIANT ARCH=$ARCH"
}

apk_paths() {
  # app APK (arch-specific) and androidTest APK (test APK is not arch-specific here)
  APP_APK="$PROJECT_ROOT/abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-${ARCH}-debug.apk"
  TEST_APK="$PROJECT_ROOT/abhaya-sensor-android/app/build/outputs/apk/androidTest/fullperm/debug/app-fullperm-debug-androidTest.apk"
  printf "%s\n%s" "$APP_APK" "$TEST_APK"
}

ensure_envs() {
  # Ensure JAVA_HOME and ANDROID_HOME exist in environment; prefer standard locations
  if [ -z "${JAVA_HOME:-}" ]; then
    if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
      export JAVA_HOME=$(/usr/libexec/java_home -v 21)
    fi
  fi
  if [ -z "${ANDROID_HOME:-}" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
  export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
}

run_build() {
  ensure_envs
  : > "$LOG_DIR/sensor_build_androidtest.log"
  cd "$PROJECT_ROOT"
  echo "Running: ./gradlew :abhaya-sensor-android:app:assemble${BUILD_VARIANT} --console=plain"
  ./gradlew ":abhaya-sensor-android:app:assemble${BUILD_VARIANT}" --console=plain 2>&1 | tee "$LOG_DIR/sensor_build_androidtest.log"
}

verify_apk_and_device() {
  ensure_envs
  read APP_APK TEST_APK <<EOF
$(apk_paths)
EOF
  echo "Built app apk: $APP_APK"
  if [ ! -f "$APP_APK" ]; then
    echo "ERROR: app APK not found: $APP_APK" >&2
    return 2
  fi
  echo "Built test apk: $TEST_APK"
  if [ ! -f "$TEST_APK" ]; then
    echo "WARNING: test APK not found: $TEST_APK" >&2
  fi
  if command -v aapt >/dev/null 2>&1; then
    aapt dump badging "$APP_APK" | grep -E "package: name|versionCode|versionName" || true
  else
    echo "(aapt not found; skipping built-apk inspection)"
  fi
  echo "Installed package info (device $DEVICE_ID):"
  adb -s "$DEVICE_ID" shell dumpsys package com.ustadmobile.meshrabiya.sensor | grep -i version || true
}

install_apks() {
  ensure_envs
  # Prefer canonical absolute APKs recorded in repo; fall back to variant-based paths.
  if [ -f "$CANONICAL_APP_APK" ]; then
    APP_APK="$CANONICAL_APP_APK"
    echo "Using canonical app APK: $APP_APK"
  else
    read APP_APK TEST_APK <<EOF
$(apk_paths)
EOF
    echo "Using computed app APK: $APP_APK"
  fi
  if [ -f "$CANONICAL_TEST_APK" ]; then
    TEST_APK="$CANONICAL_TEST_APK"
    echo "Using canonical test APK: $TEST_APK"
  else
    TEST_APK="${TEST_APK:-}"
    echo "Using computed test APK: $TEST_APK"
  fi
  if [ ! -f "$APP_APK" ]; then
    echo "ERROR: app APK not found: $APP_APK" >&2
    return 2
  fi
  # Before installing, check versionCode to avoid accidental downgrades.
  get_apk_version_code() {
    local APK="$1"
    local vc=""
    if command -v aapt >/dev/null 2>&1; then
      vc=$(aapt dump badging "$APK" 2>/dev/null | awk -F"versionCode='" '{print $2}' | awk -F"'" '{print $1}') || true
    elif command -v apkanalyzer >/dev/null 2>&1; then
      vc=$(apkanalyzer manifest print --apk "$APK" 2>/dev/null | grep -i "versionCode" | head -n1 | sed -E "s/.*versionCode[^0-9]*([0-9]+).*/\1/i") || true
    else
      echo "(warning) neither aapt nor apkanalyzer available; skipping APK versionCode check" >&2
    fi
    printf "%s" "$vc"
  }

  get_installed_version_code() {
    local PKG="$1"
    local inst_vc=""
    inst_vc=$(adb -s "$DEVICE_ID" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -E "versionCode|versionCode:" | head -n1 || true)
    # attempt to extract digits
    inst_vc=$(printf "%s" "$inst_vc" | sed -E 's/.*versionCode[^0-9]*([0-9]+).*/\1/')
    printf "%s" "$inst_vc"
  }

  PKG_NAME="com.ustadmobile.meshrabiya.sensor"
  apk_vc=$(get_apk_version_code "$APP_APK" || true)
  installed_vc=$(get_installed_version_code "$PKG_NAME" || true)
  if [ -n "$apk_vc" ] && [ -n "$installed_vc" ]; then
    if [ "$apk_vc" -lt "$installed_vc" ]; then
      echo "WARNING: APK versionCode ($apk_vc) is lower than installed versionCode ($installed_vc) on device $DEVICE_ID." >&2
      echo "The script will continue and force-install (allowing downgrade). If you prefer to prevent downgrades, change the script or bump the APK versionCode." >&2
    fi
  fi

  echo "Force-installing app APK (allow downgrade, replace, grant all runtime perms): $APP_APK"
  # Use -g to grant all runtime permissions, -r to replace, -d to allow version downgrade
  adb -s "$DEVICE_ID" install -r -d -g "$APP_APK"
  if [ -f "$TEST_APK" ]; then
    echo "Force-installing test APK: $TEST_APK"
    # Test APK should match app; allow downgrade for consistency with app install
    adb -s "$DEVICE_ID" install -r -d -g "$TEST_APK"
  else
    echo "Test APK not found; skipping test APK install"
  fi
}

run_instrumentation_log() {
  ensure_envs
  if [ "$#" -lt 2 ]; then
    echo "Usage: run_instrumentation_log <test-class-or-filter> <output-log-prefix>"
    echo "Example: run_instrumentation_log com.ustadmobile.meshrabiya.sensor.ui.SensorAppComposeTest compose_test_SensorAppComposeTest"
    return 1
  fi
  local TEST_FILTER="$1"
  local OUT_PREFIX="$2"
  local OUT_LOG="$LOG_DIR/${OUT_PREFIX}.log"
  local LOGCAT_LOG="$LOG_DIR/${OUT_PREFIX}_logcat.log"
  : > "$OUT_LOG"
  : > "$LOGCAT_LOG"
  echo "Starting logcat -> $LOGCAT_LOG"
  adb -s "$DEVICE_ID" logcat -c
  adb -s "$DEVICE_ID" logcat -v threadtime > "$LOGCAT_LOG" &
  local LOGCAT_PID=$!
  echo "Running instrumentation: class=$TEST_FILTER -> $OUT_LOG"
  adb -s "$DEVICE_ID" shell am instrument -w -r -e class "$TEST_FILTER" com.ustadmobile.meshrabiya.sensor.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT_LOG"
  echo "Killing logcat (pid=$LOGCAT_PID)"
  kill "$LOGCAT_PID" || true
  echo "Instrumentation complete. Outputs: $OUT_LOG and $LOGCAT_LOG"
}

run_instrumentation() {
  ensure_envs
  if [ "$#" -lt 2 ]; then
    echo "Usage: run_instrumentation <test-class-or-filter> <output-log>"
    echo "Example: run_instrumentation com.ustadmobile.meshrabiya.sensor.ui.SensorAppComposeTest /path/to/log.log"
    return 1
  fi
  local TEST_FILTER="$1"
  local OUT_LOG="$2"
  : > "$OUT_LOG"
  echo "Running instrumentation: class=$TEST_FILTER"
  adb -s "$DEVICE_ID" shell am instrument -w -r -e class "$TEST_FILTER" com.ustadmobile.meshrabiya.sensor.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT_LOG"
}

# End of file
