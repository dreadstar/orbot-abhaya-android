# bolt/tasks/verify_build.ps1
param(
  [string]$YES = '0'
)

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "verify_build-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[verify_build] Starting"

# Check java
if (Get-Command java -ErrorAction SilentlyContinue) {
  & java -version 2>&1 | Select-Object -First 1 | Write-Output
} else {
  Write-Output "java not found in PATH"
  Stop-Transcript
  exit 1
}

# Ensure gradlew exists
if (-not (Test-Path './gradlew')) {
  Write-Output "gradlew not found. Ensure you are at the repo root."
  Stop-Transcript
  exit 1
} else {
  & chmod +x ./gradlew
}

# Check sdkmanager
if (Get-Command sdkmanager -ErrorAction SilentlyContinue) {
  & sdkmanager --list | Select-Object -First 40 | Write-Output
} else {
  Write-Output "sdkmanager not found in PATH. Please ensure Android SDK command-line tools are installed and sdkmanager is on PATH."
  Stop-Transcript
  exit 1
}

# Run smoke builds
Write-Output "Running ./gradlew assembleDebug (main app)"
$rv = & ./gradlew assembleDebug --console=plain -x test
if ($LASTEXITCODE -ne 0) { Write-Output "Main assembleDebug failed"; Stop-Transcript; exit 1 }

# Sensor app
if (Test-Path 'abhaya-sensor-android') {
  Write-Output "Running :abhaya-sensor-android:app:assembleDebug"
  $rv2 = & ./gradlew :abhaya-sensor-android:app:assembleDebug --console=plain -x test
  if ($LASTEXITCODE -ne 0) { Write-Output "Sensor assembleDebug failed"; Stop-Transcript; exit 1 }
}

Write-Output "[verify_build] Completed successfully. Logs: $Log"
Stop-Transcript

