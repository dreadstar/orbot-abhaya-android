#!/usr/bin/env bash
# bolt/tasks/check_bolt_installed.sh
# Simple preflight to check whether the 'bolt' CLI is available and printable helpful install hints.
set -euo pipefail

if command -v bolt >/dev/null 2>&1; then
  echo "bolt is installed: $(bolt --version)"
  exit 0
fi

cat <<'EOF'
ERROR: 'bolt' (Puppet Bolt) CLI not found in PATH.

Recommended actions:
  1) Follow the official install docs: https://puppet.com/docs/bolt/latest/bolt_installing.html
  2) On macOS you can try Homebrew (formula name may vary):
       brew update
       brew search bolt
       brew install bolt   # if available
     If you previously tried 'brew install puppet-bolt' and it failed, run 'brew search puppet' or use the official installer.
  3) On Windows prefer Chocolatey or the official installer (see docs). Example (elevated PowerShell):
       choco install puppet-bolt -y || choco install bolt -y

After installing, re-run this script or run 'bolt --version' to verify.
EOF

exit 1

