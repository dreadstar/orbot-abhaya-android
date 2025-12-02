# bolt/tasks/install_android_cli_ndk.ps1
<#
Installs Android SDK command-line tools and required platforms + NDK on Windows.
This script downloads Google's command-line tools if sdkmanager is not present and uses it to install platforms and NDK.
#>
param(
  [string]$YES = '0'
)

$LogDir = "bolt\logs"
if (!(Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$TS = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $LogDir "install_android_cli_ndk-$TS.log"
Start-Transcript -Path $Log -Force

Write-Output "[install_android_cli_ndk] Starting"

# Determine SDK root
$defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if ($env:SDK_PATH -and (Test-Path $env:SDK_PATH)) { $sdk = $env:SDK_PATH }
elseif ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { $sdk = $env:ANDROID_SDK_ROOT }
elseif ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $sdk = $env:ANDROID_HOME }
elseif (Test-Path $defaultSdk) { $sdk = $defaultSdk } else { $sdk = $defaultSdk }

Write-Output "Using SDK path: $sdk"
New-Item -ItemType Directory -Path $sdk -Force | Out-Null

function Discover-CmdlineToolsUrl {
  $repoXml = 'https://dl.google.com/android/repository/repository2-1.xml'
  try {
    $tmp = New-TemporaryFile
    Invoke-WebRequest -UseBasicParsing -Uri $repoXml -OutFile $tmp -ErrorAction Stop
    $xml = Get-Content $tmp -Raw
    Remove-Item $tmp -Force
  } catch {
    Write-Output "Failed to download repository XML: $($_.Exception.Message)"
    return $null
  }

  # Find remotePackage blocks that contain cmdline-tools and extract <url>
  $urls = @()
  $matches = Select-String -InputObject $xml -Pattern '<remotePackage[^>]*>','</remotePackage>' -AllMatches
  # Simpler approach: regex find <remotePackage path="cmdline-tools[^"]*"> ... <url>...</url>
  $regex = '<remotePackage[^>]*path="cmdline-tools[^"]*"[\s\S]*?<url>([^<]+)</url>'
  $m = [regex]::Matches($xml, $regex)
  foreach ($match in $m) { $urls += $match.Groups[1].Value }

  # Prefer windows URL
  foreach ($u in $urls) {
    if ($u -match 'win' -or $u -match 'windows' -or $u -match 'commandlinetools-win') { return (If ($u -match '^https?://') { $u } else { "https://dl.google.com/android/repository/$u" }) }
  }
  if ($urls.Count -gt 0) { $u = $urls[0]; return (If ($u -match '^https?://') { $u } else { "https://dl.google.com/android/repository/$u" }) }
  return $null
}

function Ensure-SdkManager {
  # If sdkmanager present on PATH, we're done
  if (Get-Command sdkmanager -ErrorAction SilentlyContinue) {
    Write-Output "sdkmanager found at $(Get-Command sdkmanager)."
    return $true
  }

  # Download commandlinetools
  $cmdUrl = Discover-CmdlineToolsUrl
  if (-not $cmdUrl) {
    Write-Output "Could not discover cmdline-tools URL; falling back to known Windows URL"
    $cmdUrl = 'https://dl.google.com/android/repository/commandlinetools-win_latest.zip'
  }

  Write-Output "Downloading $cmdUrl"
  $tmp = New-TemporaryFile
  try {
    Invoke-WebRequest -Uri $cmdUrl -OutFile $tmp -UseBasicParsing -ErrorAction Stop
  } catch {
    Write-Output "Failed to download $cmdUrl: $($_.Exception.Message)"
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $false
  }

  # Extract
  $extractDir = Join-Path (Split-Path $tmp) 'cmdline-tools-extracted'
  New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
  try {
    Expand-Archive -Path $tmp -DestinationPath $extractDir -Force
  } catch {
    Write-Output "Failed to extract command-line tools: $($_.Exception.Message)"
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $false
  }
  Remove-Item $tmp -Force

  # Move contents to $sdk\cmdline-tools\latest
  $dest = Join-Path $sdk 'cmdline-tools\latest'
  New-Item -ItemType Directory -Path $dest -Force | Out-Null
  # extracted may contain a folder 'cmdline-tools' or direct contents
  $candidate = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
  if ($candidate -and (Test-Path (Join-Path $candidate.FullName 'bin\sdkmanager.bat'))) {
    # nested layout
    Copy-Item -Path (Join-Path $candidate.FullName '*') -Destination $dest -Recurse -Force
  } else {
    Copy-Item -Path (Join-Path $extractDir '*') -Destination $dest -Recurse -Force
  }

  # Add to PATH for this session
  $env:PATH = "${dest}\bin;${env:PATH}"

  if (Get-Command sdkmanager -ErrorAction SilentlyContinue) {
    Write-Output "sdkmanager available at $(Get-Command sdkmanager)."
    return $true
  } else {
    Write-Output "sdkmanager not found after install"
    return $false
  }
}

if (-not (Ensure-SdkManager)) {
  Write-Output "Failed to ensure sdkmanager is available. Please install Android command-line tools manually or via Android Studio."
  Stop-Transcript
  exit 1
}

# Accept licenses - this may be interactive; try to auto-accept
Write-Output "Accepting SDK licenses (may require interactive input)"
try {
  & sdkmanager --licenses
} catch {
  Write-Output "sdkmanager --licenses failed or requires interactive acceptance. Run 'sdkmanager --licenses' manually to accept."
}

# Install required packages
$packages = @('platforms;android-36','platforms;android-34','platforms;android-33','build-tools;34.0.0','ndk;27.0.12077973','platform-tools')
foreach ($p in $packages) {
  Write-Output "Installing $p"
  & sdkmanager $p
}

Write-Output "Installed packages summary (first 40 lines):"
& sdkmanager --list | Select-Object -First 40 | ForEach-Object { Write-Output $_ }

Write-Output "[install_android_cli_ndk] Completed"
Stop-Transcript
return 0

