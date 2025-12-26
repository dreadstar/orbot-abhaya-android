#!/usr/bin/env bash
# bolt/tasks/install_java.sh
# Installs or verifies Java 21 on macOS. Tries Homebrew openjdk@21, falls back to Temurin (Adoptium) auto-download and install.
set -euo pipefail

LOGDIR="bolt/logs"
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOGDIR/install_java-$TS.log"
exec > >(tee -a "$LOG") 2>&1

echo "[install_java] Starting"

# Helper: determine current java major version (if any)
current_java_major() {
  if command -v java >/dev/null 2>&1; then
    ver=$(java -version 2>&1 | awk -F '"' 'NR==1{print $2}') || true
    # parse e.g., 21.0.1 or 11.0.27
    maj=$(echo "$ver" | awk -F. '{print $1}')
    echo "$maj"
  else
    echo "0"
  fi
}

CUR_JAVA_MAJOR=$(current_java_major)
if [[ "$CUR_JAVA_MAJOR" -ge 21 ]]; then
  echo "Detected Java $CUR_JAVA_MAJOR (>=21). Nothing to install."
  exit 0
fi

# Try Homebrew first
if command -v brew >/dev/null 2>&1; then
  echo "Homebrew detected — trying Homebrew installation path first"
  install_openjdk_brew() {
    # Detect whether the current process is translated under Rosetta 2
    local proc_translated=0
    if command -v sysctl >/dev/null 2>&1; then
      if sysctl -n sysctl.proc_translated >/dev/null 2>&1; then
        proc_translated=$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)
      fi
    fi

    if [[ "$proc_translated" == "1" ]]; then
      echo "Detected Rosetta translation — using arch -arm64 brew install"
      arch -arm64 brew update || true
      arch -arm64 brew install openjdk@21 || return 1
    else
      brew update || true
      brew install openjdk@21 || return 1
    fi
    return 0
  }

  if install_openjdk_brew; then
    echo "openjdk@21 installed via Homebrew"
  else
    echo "Homebrew openjdk@21 install failed — will attempt Temurin auto-install fallback"
  fi
else
  echo "Homebrew not found — will attempt Temurin auto-install fallback"
fi

# If Java still not installed, attempt Temurin auto-download via Adoptium API
CUR_JAVA_MAJOR=$(current_java_major)
if [[ "$CUR_JAVA_MAJOR" -lt 21 ]]; then
  echo "Attempting automated Temurin (Adoptium) JDK 21 install"

  # Detect architecture
  ARCH=$(uname -m)
  if [[ "$ARCH" == "arm64" || "$ARCH" == "aarch64" ]]; then
    API_ARCH="aarch64"
  else
    API_ARCH="x64"
  fi

  # Query Adoptium API for latest Temurin 21 hotspot package (mac)
  API_URL="https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=${API_ARCH}&os=mac&image_type=jdk&heap_size=normal"
  echo "Querying Adoptium API: $API_URL"

  if command -v python3 >/dev/null 2>&1; then
    download_url=$(curl -sSf "$API_URL" | python3 -c 'import sys,json; j=json.load(sys.stdin);
try:
 for r in j:
  for b in r.get("binaries",[]):
    pkg=b.get("package",{}).get("link")
    if pkg:
      print(pkg); raise SystemExit
except SystemExit:
 pass
') || true
  else
    download_url=$(curl -sSf "$API_URL" | sed -n 's/.*"link": "\([^"]*\)".*/\1/p' | head -n1 || true)
  fi

  if [[ -z "$download_url" ]]; then
    echo "Failed to discover Temurin download URL via Adoptium API"
  else
    echo "Discovered Temurin URL: $download_url"
    TMPDIR=$(mktemp -d)
    pushd "$TMPDIR" >/dev/null
    echo "Downloading Temurin package..."
    if curl -L -O "$download_url"; then
      fname=$(ls -1)
      echo "Downloaded $fname"
      # If it's a .pkg or .dmg handle accordingly. Prefer .pkg for scripted install.
      if [[ "$fname" == *.pkg ]]; then
        echo "Installing pkg via sudo installer"
        sudo installer -pkg "$fname" -target / || { echo "sudo installer failed"; popd >/dev/null; rm -rf "$TMPDIR"; }
      elif [[ "$fname" == *.tar.gz || "$fname" == *.tgz ]]; then
        echo "Extracting tarball and installing to /Library/Java/JavaVirtualMachines"
        mkdir -p /Library/Java/JavaVirtualMachines
        tar -xzf "$fname"
        # Attempt to find top-level jdk folder and move
        extracted=$(tar -tzf "$fname" | head -n1 | cut -d/ -f1)
        if [[ -d "$extracted" ]]; then
          sudo mv "$extracted" /Library/Java/JavaVirtualMachines/ || echo "Failed to move JDK into /Library/Java/JavaVirtualMachines — please move it manually"
        fi
      elif [[ "$fname" == *.dmg ]]; then
        echo "Mounting DMG and installing (may require manual steps)"
        hdiutil attach "$fname" -nobrowse -quiet
        # Try to find a .pkg inside the mounted volume and install it
        MOUNT_POINT=$(hdiutil info | awk '//Volumes/ {print $3; exit}') || true
        if [[ -n "$MOUNT_POINT" ]]; then
          pkgfile=$(find "$MOUNT_POINT" -name "*.pkg" | head -n1 || true)
          if [[ -n "$pkgfile" ]]; then
            sudo installer -pkg "$pkgfile" -target / || echo "Failed to install pkg from dmg"
          fi
          hdiutil detach "$MOUNT_POINT" -quiet || true
        fi
      else
        echo "Unknown Temurin package format: $fname — please install JDK 21 manually from https://adoptium.net/"
      fi
    else
      echo "Failed to download Temurin from $download_url"
    fi
    popd >/dev/null
    rm -rf "$TMPDIR"
  fi
fi

# Ensure JAVA_HOME is set for the session
if [[ -x "/usr/libexec/java_home" ]]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  if [[ -n "$JAVA_HOME" ]]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Exported JAVA_HOME=$JAVA_HOME"
    # Persist JAVA_HOME for the plan to read
    mkdir -p bolt
    echo "$JAVA_HOME" > bolt/.java_home || true
  fi
fi

# Final verification
if command -v java >/dev/null 2>&1; then
  JAVA_VER=$(java -version 2>&1 | awk -F '"' 'NR==1{print $2}' || true)
  echo "java -version -> $JAVA_VER"
else
  echo "java not found after installation attempts; please install JDK 17+ manually (https://adoptium.net/)"
  exit 1
fi

echo "[install_java] Completed"
exit 0
