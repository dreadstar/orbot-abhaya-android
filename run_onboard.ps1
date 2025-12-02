# run_onboard.ps1 - PowerShell wrapper to run the onboard Bolt plan from the repo root
param(
  [switch]$Yes,
  [string]$SdkPath,
  [switch]$InstallAndroidStudio,
  [switch]$Dry
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ModulePath = Join-Path $ScriptDir 'bolt\modules'

if (-not (Get-Command bolt -ErrorAction SilentlyContinue)) {
  Write-Error "bolt CLI not found. Run .\bolt\tasks\check_bolt_installed.ps1 or install Bolt first."
  exit 2
}

$cmd = @('--modulepath', $ModulePath, 'plan','run','onboard::onboard')
if ($Yes) { $cmd += 'yes=true' }
if ($SdkPath) { $cmd += "sdk_path=$SdkPath" }
if ($InstallAndroidStudio) { $cmd += 'install_android_studio=true' }
if ($Dry) { $cmd += 'dry_run=true' }

Write-Output "Running: bolt $($cmd -join ' ')"
& bolt @cmd
