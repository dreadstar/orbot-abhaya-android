#!/usr/bin/env bash
# Install app + androidTest APKs for Orbot and Sensor modules.
# Usage: ./scripts/install_test_apks.sh <ANDROID_SERIAL>
# to find apk locations: find .  -type f -name "*.apk" -print
# "app/build/outputs/apk/fullperm/debug/app-fullperm-armeabi-v7a-debug.apk"
#"app/build/outputs/apk/fullperm/debug/app-fullperm-universal-debug.apk"
# The sensor app APK (for your device ABI, e.g. armeabi-v7a or universal):

# app-fullperm-armeabi-v7a-debug.apk
# or app-fullperm-universal-debug.apk
# The sensor app androidTest APK:

# app-fullperm-debug-androidTest.apk

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <ANDROID_SERIAL>"
  exit 2
fi

ANDROID_SERIAL="$1"
export ANDROID_SERIAL

LOG_FILE="$(pwd)/build_deploy.log"
: > "$LOG_FILE"

install_apk() {
  local apk_path="$1"
  echo "Force installing $apk_path to device $ANDROID_SERIAL" | tee -a "$LOG_FILE"
  adb -s "$ANDROID_SERIAL" install -r -d -g "$apk_path" 2>&1 | tee -a "$LOG_FILE"
}

# Paths (relative to repository root)
APPS=(
  "app/build/outputs/apk/fullperm/debug/app-fullperm-armeabi-v7a-debug.apk"
  "app/build/outputs/apk/androidTest/fullperm/debug/app-fullperm-debug-androidTest.apk"
  "abhaya-sensor-android/app/build/outputs/apk/androidTest/fullperm/debug/app-fullperm-debug-androidTest.apk"
  "abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-armeabi-v7a-debug.apk"

)


# Simple install loop (no deduplication)
for apk in "${APPS[@]}"; do
# ...existing code...
  if [ ! -f "$apk" ]; then
    echo "WARNING: APK not found: $apk (skipping)" | tee -a "$LOG_FILE"
    continue
  fi
  install_apk "$apk"
done

echo "Install finished. Logs appended to $LOG_FILE" | tee -a "$LOG_FILE"
