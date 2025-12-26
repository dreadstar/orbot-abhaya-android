#!/usr/bin/env bash
# bolt/tasks/install_android_cli_ndk.sh
# Installs Android SDK command-line tools and required platforms + NDK on macOS without relying on Homebrew for cmdline-tools.
set -euo pipefail

LOGDIR="bolt/logs"
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOGDIR/install_android_cli_ndk-$TS.log"
exec > >(tee -a "$LOG") 2>&1

echo "[install_android_cli_ndk] Starting"

# Use JAVA_HOME from environment if provided, otherwise try to read from persisted file
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
  echo "Using JAVA_HOME from environment: ${JAVA_HOME}"
elif [[ -f "bolt/.java_home" ]]; then
  JAVA_HOME=$(cat bolt/.java_home 2>/dev/null || true)
  if [[ -n "$JAVA_HOME" ]]; then
    export JAVA_HOME
    export PATH="${JAVA_HOME}/bin:${PATH}"
    echo "Using JAVA_HOME from bolt/.java_home: ${JAVA_HOME}"
  fi
fi

# Verify JAVA_HOME is set
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "Java version check:"
  java -version 2>&1 | head -n1 || true
  echo "which java: $(which java)"
else
  echo "WARNING: JAVA_HOME not set. Attempting to use system Java."
  echo "Current java version:"
  java -version 2>&1 | head -n1 || true
fi

# Respect SDK_PATH if provided
SDKROOT="${SDK_PATH:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}}"
if [[ -z "$SDKROOT" ]]; then
  SDKROOT="$HOME/Library/Android/sdk"
fi

echo "Using SDK path: $SDKROOT"
mkdir -p "$SDKROOT"

# Ensure curl/unzip are present
if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  echo "Error: Neither curl nor wget found. Please install one to download Android command-line tools."
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "Error: unzip is required to extract the command-line tools. Please install unzip (brew install unzip)."
  exit 1
fi

# Function to discover the best commandlinetools URL from Google's repository XML
discover_cmdline_tools_url() {
  local repo_xml_url='https://dl.google.com/android/repository/repository2-1.xml'
  local tmprepo
  tmprepo=$(mktemp)
  if command -v curl >/dev/null 2>&1; then
    curl -sSf "$repo_xml_url" -o "$tmprepo" || { rm -f "$tmprepo"; return 1; }
  else
    wget -q -O "$tmprepo" "$repo_xml_url" || { rm -f "$tmprepo"; return 1; }
  fi

  # Look for remotePackage blocks containing 'cmdline-tools' and extract <url> entries
  local besturl=""
  # Read the file and split by </remotePackage>
  awk 'BEGIN{RS="</remotePackage>"} /cmdline-tools/ { if (match($0, /<url>[^<]+<\/url>/)) { while(match($0, /<url>[^<]+<\/url>/)) { u=substr($0, RSTART, RLENGTH); sub(/<url>/, "", u); sub(/<\/url>/, "", u); print u; $0 = substr($0, RSTART+RLENGTH) } } }' "$tmprepo" > /tmp/cmd_urls.$$ || true

  # Prefer a URL that contains mac or darwin
  if [[ -f /tmp/cmd_urls.$$ ]]; then
    while read -r u; do
      if [[ "$u" =~ mac || "$u" =~ darwin || "$u" =~ Mac || "$u" =~ -mac ]]; then
        besturl="$u"
        break
      elif [[ -z "$besturl" ]]; then
        besturl="$u"
      fi
    done < /tmp/cmd_urls.$$
    rm -f /tmp/cmd_urls.$$
  fi

  rm -f "$tmprepo"
  if [[ -n "$besturl" ]]; then
    if [[ "$besturl" =~ ^https?:// ]]; then
      echo "$besturl"
    else
      echo "https://dl.google.com/android/repository/$besturl"
    fi
    return 0
  fi
  return 1
}

# Function to ensure sdkmanager available by downloading commandlinetools
ensure_sdkmanager() {
  if command -v sdkmanager >/dev/null 2>&1; then
    echo "sdkmanager already available at $(command -v sdkmanager)"
    return 0
  fi

  echo "sdkmanager not found. Installing Android command-line tools into ${SDKROOT}/cmdline-tools/latest"
  mkdir -p "$SDKROOT/cmdline-tools"
  TMPDIR=$(mktemp -d)
  pushd "$TMPDIR" >/dev/null

  # Discover URL
  echo "Discovering latest command-line tools URL from Google's repository..."
  CMDLINE_URL=$(discover_cmdline_tools_url) || true
  if [[ -z "$CMDLINE_URL" ]]; then
    echo "Automatic discovery failed; falling back to a known (may be outdated) URL"
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-mac_latest.zip"
  fi

  echo "Downloading ${CMDLINE_URL}"
  if command -v curl >/dev/null 2>&1; then
    curl -fLO "$CMDLINE_URL" || { echo "Failed to download $CMDLINE_URL"; popd >/dev/null; rm -rf "$TMPDIR"; return 1; }
  else
    wget "$CMDLINE_URL" -O commandlinetools.zip || { echo "Failed to download $CMDLINE_URL"; popd >/dev/null; rm -rf "$TMPDIR"; return 1; }
  fi

  # Extract
  unzip -q *.zip || { echo "Failed to unzip command-line tools"; popd >/dev/null; rm -rf "$TMPDIR"; return 1; }

  # Move contents into $SDKROOT/cmdline-tools/latest
  if [[ -d "cmdline-tools" ]]; then
    EXTRACTED_DIR="cmdline-tools"
  else
    EXTRACTED_DIR=$(find . -maxdepth 2 -type d -name "cmdline-tools*" | head -n1 || true)
  fi
  if [[ -z "$EXTRACTED_DIR" ]]; then
    echo "Unable to locate extracted cmdline-tools directory"
    popd >/dev/null
    rm -rf "$TMPDIR"
    return 1
  fi

  rm -rf "$SDKROOT/cmdline-tools/latest" || true
  mkdir -p "$SDKROOT/cmdline-tools/latest"
  if [[ -d "$EXTRACTED_DIR/cmdline-tools" ]]; then
    mv "$EXTRACTED_DIR/cmdline-tools"/* "$SDKROOT/cmdline-tools/latest/"
  else
    mv "$EXTRACTED_DIR"/* "$SDKROOT/cmdline-tools/latest/"
  fi

  popd >/dev/null
  rm -rf "$TMPDIR"

  # Put sdkmanager on PATH for this session
  export PATH="$SDKROOT/cmdline-tools/latest/bin:$PATH"

  if command -v sdkmanager >/dev/null 2>&1; then
    echo "sdkmanager installed successfully"
    return 0
  else
    echo "sdkmanager still not found after installation"
    return 1
  fi
}

# Ensure sdkmanager (downloaded if necessary)
if ! ensure_sdkmanager; then
  echo "Failed to ensure sdkmanager is available. Please install command-line tools manually or via Android Studio."
  exit 1
fi

# Accept licenses non-interactively
# Ensure JAVA_HOME is used by sdkmanager
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi
yes | sdkmanager --licenses || true

# Install required packages via sdkmanager (idempotent)
REQUIRED_PACKAGES=("platforms;android-36" "platforms;android-34" "platforms;android-33" "build-tools;34.0.0" "ndk;27.0.12077973" "platform-tools")
for pkg in "${REQUIRED_PACKAGES[@]}"; do
  echo "Installing $pkg"
  # Ensure JAVA_HOME is set for each sdkmanager call
  if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="${JAVA_HOME}" PATH="${JAVA_HOME}/bin:${PATH}" sdkmanager "$pkg" || { echo "Failed to install $pkg"; exit 1; }
  else
    sdkmanager "$pkg" || { echo "Failed to install $pkg"; exit 1; }
  fi
done

# Verify installation
echo "Installed packages:"
sdkmanager --list | head -n 40 || true

echo "[install_android_cli_ndk] Completed"
exit 0
