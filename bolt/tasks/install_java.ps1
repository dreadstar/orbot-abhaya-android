# bolt/tasks/install_java.ps1
<#
Installs Java 21 (Temurin) on Windows. Tries Chocolatey first, then falls back to direct MSI from Adoptium.
#>
param(
  [switch]$YES = $false
)

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "install_java-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[install_java.ps1] Starting"

function Get-JavaMajorVersion {
  try {
    $v = & java -version 2>&1 | Select-String -Pattern '"([0-9]+)' -AllMatches
    if ($v.Matches.Count -gt 0) { return [int]$v.Matches[0].Groups[1].Value }
  } catch { }
  return 0
}

$maj = Get-JavaMajorVersion
if ($maj -ge 21) {
  Write-Output "Java $maj detected; nothing to do."
  Stop-Transcript
  exit 0
}

# Try Chocolatey
if (Get-Command choco -ErrorAction SilentlyContinue) {
  Write-Output "Chocolatey detected — attempting choco install temurin21"
  try {
    choco install temurin21 -y --no-progress
    Start-Sleep -Seconds 2
    $maj = Get-JavaMajorVersion
    if ($maj -ge 21) { Write-Output "Temurin 21 installed via Chocolatey"; Stop-Transcript; exit 0 }
  } catch { Write-Output "choco install temurin21 failed: $($_.Exception.Message)" }
}

# Fallback: download MSI from Adoptium API
Write-Output "Attempting to discover Temurin 21 MSI via Adoptium API"
$api = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&os=windows&image_type=jdk&heap_size=normal'
try {
  $json = Invoke-RestMethod -Uri $api -UseBasicParsing -ErrorAction Stop
  $link = $json[0].binaries[0].package.link
} catch {
  Write-Output "Failed to query Adoptium API: $($_.Exception.Message)"
  $link = $null
}

if (-not $link) {
  Write-Output "Could not discover MSI via API; you can download Temurin 21 manually from https://adoptium.net/"
  Stop-Transcript
  exit 1
}

$dest = Join-Path $env:TEMP "temurin21.msi"
Write-Output "Downloading $link to $dest"
try {
  Invoke-WebRequest -Uri $link -OutFile $dest -UseBasicParsing -ErrorAction Stop
} catch {
  Write-Output "Failed to download MSI: $($_.Exception.Message)"
  Stop-Transcript
  exit 1
}

Write-Output "Installing Temurin 21 MSI..."
$proc = Start-Process msiexec.exe -ArgumentList "/i `"$dest`" /qn /norestart" -Wait -PassThru
if ($proc.ExitCode -ne 0) {
  Write-Output "msiexec returned exit code $($proc.ExitCode)"
  Stop-Transcript
  exit 1
}

Start-Sleep -Seconds 2
$maj = Get-JavaMajorVersion
if ($maj -ge 21) { Write-Output "Temurin 21 installed successfully" } else { Write-Output "Temurin appears not to be installed correctly. JAVA_HOME may need to be set." }

Stop-Transcript
exit 0

