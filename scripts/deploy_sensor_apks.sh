#!/usr/bin/env bash
set -euo pipefail

# Script runs sequentially; no background child processes are created.

# Deploy sensor APKs to a device and run instrumentation tests.
# Usage: ./scripts/deploy_sensor_apks.sh [DEVICE_ID] [TESTS]
#
# TESTS (optional): comma-separated list of instrumentation test class names to run.
# If omitted (or set to the literal string 'all'), the script will run the entire
# instrumentation APK (i.e. all instrumentation tests) instead of selecting specific
# test classes.

DEVICE="${1:-${DEVICE:-30870044490006E}}"
# Optional second parameter (or env var TESTS) - comma separated list of test class names
# Examples:
#  ./scripts/deploy_sensor_apks.sh 30870044490006E com.example.MyTest
#  ./scripts/deploy_sensor_apks.sh 30870044490006E com.example.TestA,com.example.TestB
TESTS="${2:-${TESTS:-}}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

WORKSPACE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_APK_DIR="$WORKSPACE_ROOT/abhaya-sensor-android/app/build/outputs/apk/fullperm/debug"
TEST_APK_DIR="$WORKSPACE_ROOT/abhaya-sensor-android/app/build/outputs/apk/androidTest/fullperm/debug"

echo "[deploy_sensor_apks] Device: $DEVICE"
echo "[deploy_sensor_apks] Building app + androidTest APKs (assembleFullpermDebugAndroidTest)"
BUILD_LOG="$WORKSPACE_ROOT/build_deploy.log"
: > "$BUILD_LOG"
# Invoke the gradle wrapper by absolute path so we don't need to change directories.
GRADLEW="$WORKSPACE_ROOT/gradlew"
echo "[deploy_sensor_apks] Starting Gradle build (foreground)" | tee -a "$BUILD_LOG"
if [ -x "$GRADLEW" ]; then
  # Run wrapper directly; keep --no-daemon to avoid a persistent daemon.
  # Run wrapper in foreground and tee output to BUILD_LOG (preserve exit code)
  "$GRADLEW" :abhaya-sensor-android:app:assembleFullpermDebugAndroidTest --console=plain --no-daemon 2>&1 | tee -a "$BUILD_LOG"
  GRADLE_RC=${PIPESTATUS[0]}
else
  # Use bash wrapper invocation in foreground and tee output
  bash "$GRADLEW" :abhaya-sensor-android:app:assembleFullpermDebugAndroidTest --console=plain --no-daemon 2>&1 | tee -a "$BUILD_LOG"
  GRADLE_RC=${PIPESTATUS[0]}
fi
if [ "$GRADLE_RC" -ne 0 ]; then
  echo "[deploy_sensor_apks] ERROR: gradle assemble failed (exit $GRADLE_RC)" | tee -a "$BUILD_LOG" >&2
  exit $GRADLE_RC
fi

# helper: install an APK and verify success
install_apk() {
  local apkpath="$1"
  echo "[deploy_sensor_apks] Installing $apkpath" | tee -a "$BUILD_LOG"
  # Run adb install in foreground and append output to BUILD_LOG via tee
  adb -s "$DEVICE" install -r -d -g "$apkpath" 2>&1 | tee -a "$BUILD_LOG"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then
    echo "[deploy_sensor_apks] ERROR: failed to install $apkpath (exit $rc)" | tee -a "$BUILD_LOG" >&2
    return $rc
  fi
  echo "[deploy_sensor_apks] Successfully installed $apkpath" | tee -a "$BUILD_LOG"
  return 0
}

# Detect device ABI
ABI_LIST=$(adb -s "$DEVICE" shell getprop ro.product.cpu.abilist | tr -d '\r')
PRIMARY_ABI=$(adb -s "$DEVICE" shell getprop ro.product.cpu.abi | tr -d '\r')
echo "[deploy_sensor_apks] Device ABI list: $ABI_LIST"
echo "[deploy_sensor_apks] Device primary ABI: $PRIMARY_ABI"

echo "[deploy_sensor_apks] Scanning APK directories"
echo "  app apks dir: $APP_APK_DIR"
echo "  test apks dir: $TEST_APK_DIR"

APP_APKS=()
# Populate APP_APKS using shell globbing to keep execution linear.
for apk in "$APP_APK_DIR"/*.apk; do
  if [ -f "$apk" ]; then
    APP_APKS+=("$apk")
  fi
done

TEST_APKS=()
for apk in "$TEST_APK_DIR"/*.apk; do
  if [ -f "$apk" ]; then
    TEST_APKS+=("$apk")
  fi
done

echo "[deploy_sensor_apks] Found app APKs:" && for p in "${APP_APKS[@]:-}"; do echo "  - $p"; done
echo "[deploy_sensor_apks] Found test APKs:" && for p in "${TEST_APKS[@]:-}"; do echo "  - $p"; done

# Choose app APKs to install: prefer primary ABI; if none, use universal; warn if multiple ABI matches
APKS_TO_INSTALL=()
for apk in "${APP_APKS[@]:-}"; do
  fname=$(basename "$apk")
  if [[ "$fname" == *"$PRIMARY_ABI"* ]]; then
    APKS_TO_INSTALL+=("$apk")
  fi
done

if [ ${#APKS_TO_INSTALL[@]} -eq 0 ]; then
  # try universal
  for apk in "${APP_APKS[@]:-}"; do
    fname=$(basename "$apk")
    if [[ "$fname" == *"universal"* ]]; then
      APKS_TO_INSTALL+=("$apk")
    fi
  done
fi

# Fallback: if still empty, pick the smallest non-x86 apk that looks like arm or just the first
if [ ${#APKS_TO_INSTALL[@]} -eq 0 ] && [ ${#APP_APKS[@]} -gt 0 ]; then
  for apk in "${APP_APKS[@]}"; do
    fname=$(basename "$apk")
    if [[ "$fname" == *"arm"* || "$fname" == *"armeabi"* ]]; then
      APKS_TO_INSTALL+=("$apk")
    fi
  done
fi
if [ ${#APKS_TO_INSTALL[@]} -eq 0 ] && [ ${#APP_APKS[@]} -gt 0 ]; then
  APKS_TO_INSTALL+=("${APP_APKS[0]}")
fi

if [ ${#APKS_TO_INSTALL[@]} -eq 0 ]; then
  echo "[deploy_sensor_apks] ERROR: no app APKs found to install" >&2
  exit 1
fi

echo "[deploy_sensor_apks] Selected app APK(s) to install:" && for p in "${APKS_TO_INSTALL[@]}"; do echo "  - $p"; done

# For test APKs, install all found test APKs (usually one)
TESTS_TO_INSTALL=("${TEST_APKS[@]:-}")
if [ ${#TESTS_TO_INSTALL[@]} -eq 0 ]; then
  echo "[deploy_sensor_apks] WARNING: no androidTest APK found" >&2
fi

echo "[deploy_sensor_apks] Installing on device $DEVICE"
for apk in "${APKS_TO_INSTALL[@]}"; do
  install_apk "$apk" || exit 2
done
for apk in "${TESTS_TO_INSTALL[@]:-}"; do
  install_apk "$apk" || exit 3
done

# Save last deployed paths for reference
LAST_DEPLOY_FILE="$WORKSPACE_ROOT/.last_deployed_sensor_apks"
{
  for p in "${APKS_TO_INSTALL[@]}"; do echo "$p"; done
  for p in "${TESTS_TO_INSTALL[@]:-}"; do echo "$p"; done
} > "$LAST_DEPLOY_FILE"
echo "[deploy_sensor_apks] Saved last deployed APKs to $LAST_DEPLOY_FILE"

echo "[deploy_sensor_apks] Running instrumentation tests"
# If TESTS is empty or 'all' -> run the entire instrumentation APK (all tests)
OUT_LOGS=()
if [ -z "$TESTS" ] || [ "$TESTS" = "all" ]; then
  OUT="$WORKSPACE_ROOT/compose_test_instrumentation_all.log"
  echo "[deploy_sensor_apks] Running full instrumentation (all tests)" | tee -a "$BUILD_LOG"
  # Run instrumentation in foreground and tee output to both console and the OUT log file
  adb -s "$DEVICE" shell am instrument -w -r com.ustadmobile.meshrabiya.sensor.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT"
  INST_RC=${PIPESTATUS[0]}
  echo "[deploy_sensor_apks] Full instrumentation exit code: $INST_RC" | tee -a "$BUILD_LOG"
  OUT_LOGS+=("$OUT")
else
  # Split comma-separated TESTS into array
  IFS=',' read -r -a TEST_ARRAY <<< "$TESTS"
  idx=1
  for class in "${TEST_ARRAY[@]}"; do
    # sanitize classname for filename
    safe_name=$(echo "$class" | sed 's#[^A-Za-z0-9_]#_#g')
    OUT="$WORKSPACE_ROOT/compose_test_${safe_name}.log"
    echo "[deploy_sensor_apks] Running instrumentation for class: $class" | tee -a "$BUILD_LOG"
  # Run class-specific instrumentation and tee output to OUT
  adb -s "$DEVICE" shell am instrument -w -r -e class "$class" com.ustadmobile.meshrabiya.sensor.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$OUT"
  rc=${PIPESTATUS[0]}
    echo "[deploy_sensor_apks] Instrumentation $class exit code: $rc" | tee -a "$BUILD_LOG"
    OUT_LOGS+=("$OUT")
    idx=$((idx+1))
  done
fi

echo "[deploy_sensor_apks] Instrumentation logs:"
for p in "${OUT_LOGS[@]:-}"; do echo "  - $p"; done

echo "[deploy_sensor_apks] Done."
