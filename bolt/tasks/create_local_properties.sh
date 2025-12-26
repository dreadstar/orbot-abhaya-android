#!/usr/bin/env bash
# bolt/tasks/create_local_properties.sh
# Auto-detect Android SDK path and write local.properties using default paths if not found.
set -euo pipefail

LOGDIR="bolt/logs"
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOGDIR/create_local_properties-$TS.log"
exec > >(tee -a "$LOG") 2>&1

echo "[create_local_properties] Starting"
PROJECT_ROOT="$(pwd)"
LOCAL_PROPERTIES="$PROJECT_ROOT/local.properties"

# Default paths
MAC_DEFAULT="$HOME/Library/Android/sdk"
# Do not fail if LOCALAPPDATA is unset; make WIN_DEFAULT empty if not present
WIN_DEFAULT="${LOCALAPPDATA:-}"
if [[ -n "$WIN_DEFAULT" ]]; then
  WIN_DEFAULT="$WIN_DEFAULT/Android/Sdk"
fi

# Respect explicit SDK_PATH env if provided
if [[ -n "${SDK_PATH:-}" ]]; then
  echo "Using SDK_PATH from environment: ${SDK_PATH}"
  if [[ -d "$SDK_PATH" ]]; then
    SDKPATH="$SDK_PATH"
  else
    echo "Warning: SDK_PATH (${SDK_PATH}) does not exist. Will proceed and write it into local.properties; you may need to install SDK or update the path."
    SDKPATH="$SDK_PATH"
  fi
else
  # Try environment variables first
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "${ANDROID_SDK_ROOT}" ]]; then
    SDKPATH="$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "${ANDROID_HOME}" ]]; then
    SDKPATH="$ANDROID_HOME"
  elif [[ -d "$MAC_DEFAULT" ]]; then
    SDKPATH="$MAC_DEFAULT"
  else
    SDKPATH=""
  fi
fi

# If SDK path not found, prompt (unless YES=1)
YES=${YES:-0}
if [[ -z "$SDKPATH" ]]; then
  if [[ "$YES" == "1" ]]; then
    echo "No SDK detected; using default: $MAC_DEFAULT (you may need to adjust local.properties)"
    SDKPATH="$MAC_DEFAULT"
  else
    read -p "Android SDK not detected. Enter SDK path or press Enter to use default ($MAC_DEFAULT): " inp || true
    inp=${inp:-$MAC_DEFAULT}
    SDKPATH="$inp"
  fi
fi

# Normalize path on macOS
if [[ "$SDKPATH" == "~"* ]]; then
  SDKPATH="${SDKPATH/#~/$HOME}"
fi

# Write local.properties
echo "sdk.dir=${SDKPATH}" > "$LOCAL_PROPERTIES"
chmod 644 "$LOCAL_PROPERTIES"

echo "Wrote $LOCAL_PROPERTIES with sdk.dir=${SDKPATH}"

# Export JAVA_HOME for session if possible
if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 21 || true)
  echo "Exported JAVA_HOME=${JAVA_HOME} (session)"
fi

# Document INSTALL_ANDROID_STUDIO env if present
if [[ -n "${INSTALL_ANDROID_STUDIO:-}" ]]; then
  echo "INSTALL_ANDROID_STUDIO=${INSTALL_ANDROID_STUDIO}"
fi

echo "[create_local_properties] Completed"
exit 0
