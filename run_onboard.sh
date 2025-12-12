#!/usr/bin/env bash
# run_onboard.sh - wrapper to run the onboard Bolt plan from the repo root
# Usage: ./run_onboard.sh [--yes] [--sdk-path /path/to/sdk] [--install-android-studio]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULEPATH="$REPO_ROOT/bolt/modules"

YES_FLAG=0
SDK_PATH=""
INSTALL_ANDROID_STUDIO=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes)
      YES_FLAG=1; shift ;;
    --sdk-path)
      SDK_PATH="$2"; shift 2 ;;
    --install-android-studio)
      INSTALL_ANDROID_STUDIO=1; shift ;;
    --dry-run)
      DRY_RUN=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--yes] [--sdk-path PATH] [--install-android-studio] [--dry-run]"; exit 0 ;;
    *)
      echo "Unknown arg: $1"; echo "Usage: $0 [--yes] [--sdk-path PATH] [--install-android-studio] [--dry-run]"; exit 1 ;;
  esac
done

# Check bolt
if ! command -v bolt >/dev/null 2>&1; then
  echo "bolt CLI not found. Run bolt/tasks/check_bolt_installed.sh or install Bolt first." >&2
  exit 2
fi

CMD=(bolt plan run onboard::onboard --modulepath "$MODULEPATH")
# Add plan parameters
if [[ "$YES_FLAG" == "1" ]]; then
  CMD+=(yes=true)
fi
if [[ -n "$SDK_PATH" ]]; then
  CMD+=(sdk_path="$SDK_PATH")
fi
if [[ "$INSTALL_ANDROID_STUDIO" == "1" ]]; then
  CMD+=(install_android_studio=true)
fi
if [[ "$DRY_RUN" == "1" ]]; then
  CMD+=(dry_run=true)
fi

# Print and run
echo "Running: ${CMD[*]}"
"${CMD[@]}"
