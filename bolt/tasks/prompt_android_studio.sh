#!/usr/bin/env bash
# bolt/tasks/prompt_android_studio.sh
# Prompt user about installing Android Studio GUI (do not auto-install unless explicitly allowed).
set -euo pipefail

YES=${YES:-0}
LOGDIR="bolt/logs"
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOGDIR/prompt_android_studio-$TS.log"
exec > >(tee -a "$LOG") 2>&1

echo "[prompt_android_studio] Starting"
if [[ "$YES" == "1" ]]; then
  echo "--yes provided: skipping Android Studio GUI prompt. To install Android Studio later, visit https://developer.android.com/studio"
  exit 0
fi

read -p "Would you like to install Android Studio GUI now? (y/N): " ans || true
ans=${ans:-N}
if [[ "$ans" =~ ^[Yy]$ ]]; then
  echo "Installing Android Studio via Homebrew cask..."
  if ! command -v brew >/dev/null 2>&1; then
    echo "Homebrew not found. Please install Android Studio manually: https://developer.android.com/studio"
    exit 1
  fi
  brew update || true
  brew install --cask android-studio || { echo "Failed to install Android Studio via Homebrew. Please install manually."; exit 1; }
  echo "Android Studio installed."
else
  echo "Skipping Android Studio GUI installation. You can install it later: https://developer.android.com/studio"
fi

echo "[prompt_android_studio] Completed"
exit 0

