# bolt/tests/run_tests.ps1
# Non-destructive tests for onboarding scripts (Windows PowerShell)

$ErrorActionPreference = 'Stop'
Write-Output "Running onboarding scripts basic tests (PowerShell)"

# Ensure scripts exist
$shScripts = Get-ChildItem -Path ..\..\bolt\tasks\*.ps1 -File
foreach ($f in $shScripts) { Write-Output "Found $f" }

# Test create_local_properties.ps1 in a temp dir
$temp = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ([System.Guid]::NewGuid().ToString()))
Push-Location $temp.FullName

# Provide SDK_PATH to a temp location
New-Item -ItemType Directory -Path "sdk_fake" | Out-Null
$env:SDK_PATH = (Join-Path $temp.FullName 'sdk_fake')
$env:YES = '1'

# Run the script
& (Join-Path $PSScriptRoot '..\..\bolt\tasks\create_local_properties.ps1') -YES 1
Get-Content local.properties | Write-Output

Pop-Location
Remove-Item -Recurse -Force $temp.FullName

# Test init_submodules.ps1 DRY_RUN=1
$env:DRY_RUN = '1'
& (Join-Path $PSScriptRoot '..\..\bolt\tasks\init_submodules.ps1')

Write-Output "All PowerShell tests passed (non-destructive)"
exit 0

