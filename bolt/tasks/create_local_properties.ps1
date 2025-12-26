# bolt/tasks/create_local_properties.ps1
param(
  [string]$YES = "0"
)

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "create_local_properties-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[create_local_properties] Starting"
$ProjectRoot = Get-Location
$LocalProperties = Join-Path $ProjectRoot 'local.properties'

$default = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { $sdk = $env:ANDROID_SDK_ROOT }
elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $sdk = $env:ANDROID_HOME }
elseif (Test-Path $default) { $sdk = $default }
else { $sdk = $null }

if (-not $sdk) {
  if ($YES -eq '1') {
    Write-Output "No SDK detected. Using default: $default"
    $sdk = $default
  } else {
    $input = Read-Host "Android SDK not detected. Enter SDK path or press Enter to use default ($default)"
    if ([string]::IsNullOrWhiteSpace($input)) { $sdk = $default } else { $sdk = $input }
  }
}

# Write local.properties
"sdk.dir=$sdk" | Out-File -FilePath $LocalProperties -Encoding UTF8
Write-Output "Wrote $LocalProperties with sdk.dir=$sdk"

# Export JAVA_HOME for session (PowerShell) - do not persist
try {
  $javaHome = & ("/usr/libexec/java_home" -ErrorAction SilentlyContinue) 2>$null
} catch { $javaHome = $null }

if ($javaHome) { Write-Output "Detected JAVA_HOME: $javaHome" }
else { Write-Output "Please ensure JAVA_HOME is set to your Java 21 installation if needed." }

Write-Output "[create_local_properties] Completed"
Stop-Transcript

