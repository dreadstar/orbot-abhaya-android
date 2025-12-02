# bolt/tasks/check_bolt_installed.ps1
$ErrorActionPreference = 'Stop'
try {
  $v = & bolt --version 2>&1
  Write-Output "bolt is installed: $v"
  exit 0
} catch {
  Write-Output "ERROR: 'bolt' (Puppet Bolt) CLI not found in PATH."
  Write-Output "Recommended actions:"
  Write-Output "  1) Follow the official install docs: https://puppet.com/docs/bolt/latest/bolt_installing.html"
  Write-Output "  2) On Windows you can try Chocolatey (elevated PowerShell): choco install puppet-bolt -y || choco install bolt -y"
  Write-Output "  3) After installing, re-run this script or run 'bolt --version' to verify."
  exit 1
}

