# bolt/tasks/init_submodules.ps1
# Initialize git submodules using SSH. Detect SSH key presence and instruct if missing. Do not create keys.

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "init_submodules-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[init_submodules] Starting"
$ProjectRoot = Get-Location

# Ensure git repo
if (-not (Test-Path (Join-Path $ProjectRoot '.git'))) {
  Write-Output "Not inside a git repository. Please run this script from the repository root."
  Stop-Transcript
  exit 1
}

if (-not (Test-Path (Join-Path $ProjectRoot '.gitmodules'))) {
  Write-Output ".gitmodules not found. No submodules to init."
  Stop-Transcript
  exit 0
}

# Check for SSH agent and keys
$sshOk = $false
if ($env:SSH_AUTH_SOCK) {
  Write-Output "SSH agent appears to be running"
  try {
    $ids = & ssh-add -l
    if ($ids) { Write-Output "SSH agent has identities"; $sshOk = $true }
  } catch { Write-Output "ssh-add may not be available or agent has no identities" }
}

# Check for local keys
if (-not $sshOk) {
  $keys = @('id_ed25519','id_rsa','id_ecdsa') | ForEach-Object { Join-Path $env:USERPROFILE (".ssh\$_") } | Where-Object { Test-Path $_ }
  if ($keys) {
    Write-Output "Found SSH keys: $keys"
    Write-Output "Ensure your private key is added to the SSH agent: ssh-add <path-to-private-key> and that your public key is uploaded to GitHub: https://github.com/settings/keys"
  } else {
    Write-Output "No SSH keys found in ~/.ssh. Please add an existing SSH key and upload the public key to GitHub: https://github.com/settings/keys"
  }

  Write-Output "Attempting SSH test to git@github.com"
  $test = & ssh -T git@github.com 2>&1
  if ($test -match 'successfully authenticated') { Write-Output "SSH to github.com succeeded"; $sshOk = $true }
  else {
    Write-Output "SSH authentication to GitHub failed. Aborting submodule init. Follow these steps to fix:"
    Write-Output "  1) Ensure you have an SSH key (see https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-ssh-keys)"
    Write-Output "  2) Add your private key to the agent: ssh-add ~/.ssh/id_rsa"
    Write-Output "  3) Verify with: ssh -T git@github.com"
    Write-Output "  4) Once SSH access works, re-run this plan to initialize submodules"
    Stop-Transcript
    exit 1
  }
}

# Ensure submodules use SSH (fail if HTTPS)
$gitmodules = Get-Content -Path (Join-Path $ProjectRoot '.gitmodules')
if ($gitmodules -match 'https://') {
  Write-Output "Detected HTTPS submodule URLs in .gitmodules. This plan enforces SSH-only submodules. Please convert URLs to SSH in .gitmodules and re-run."
  Stop-Transcript
  exit 1
}

$DRY_RUN = $env:DRY_RUN
if ($DRY_RUN -eq '1') {
  Write-Output "DRY_RUN=1: Skipping actual git submodule update. (Would run: git submodule sync --recursive && git submodule update --init --recursive)"
} else {
  # Sync and update submodules
  Write-Output "Initializing submodules via SSH..."
  & git submodule sync --recursive
  & git submodule update --init --recursive
}

Write-Output "Submodules initialized successfully"

Write-Output "[init_submodules] Completed"
Stop-Transcript
