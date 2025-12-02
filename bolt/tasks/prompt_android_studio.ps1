# bolt/tasks/prompt_android_studio.ps1
param(
  [string]$YES = "0"
)

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "prompt_android_studio-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[prompt_android_studio] Starting"
if ($YES -eq '1') {
  Write-Output "--yes provided: skipping Android Studio GUI prompt. To install Android Studio later, visit https://developer.android.com/studio"
  Stop-Transcript
  exit 0
}

$choice = Read-Host "Would you like to install Android Studio GUI now? (y/N)"
if ($choice -match '^[Yy]') {
  Write-Output "Installing Android Studio via Chocolatey..."
  if (Get-Command choco -ErrorAction SilentlyContinue) {
    choco install -y androidstudio || Write-Output "choco install androidstudio failed; please install manually"
  } elseif (Get-Command winget -ErrorAction SilentlyContinue) {
    Write-Output "Chocolatey not found; attempting winget..."
    winget install --id Google.AndroidStudio -e --silent
  } else {
    Write-Output "Neither choco nor winget found. Please install Android Studio manually: https://developer.android.com/studio"
    Stop-Transcript
    exit 1
  }
  Write-Output "If installation succeeded, launch Android Studio to complete SDK setup."
} else {
  Write-Output "Skipping Android Studio GUI installation. You can install it later: https://developer.android.com/studio"
}

Write-Output "[prompt_android_studio] Completed"
Stop-Transcript

