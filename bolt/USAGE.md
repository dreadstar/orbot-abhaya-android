# Bolt Onboarding Plan - USAGE

This document describes how to run the onboarding plan and task scripts for the Orbot project.

Quick summary
- A Bolt module plan is available: `bolt/modules/onboard/plans/onboard.pp`.
- Run with Bolt: `bolt plan run onboard::onboard yes=true` (or `yes=false`).
- Tasks are in `bolt/tasks/` and can be run individually for troubleshooting.
- Scripts log to `bolt/logs/`.

## Installing the Bolt (Puppet Bolt) CLI (required)

Before running the module plan with Bolt, install the Bolt CLI. Many of the provided helpers assume `bolt` is available and runnable; without it the single-command plan experience won't work — you can still run individual scripts directly, but installing Bolt is recommended.

Primary (recommended): official install guide
- The most reliable way to install Bolt is to follow the official installation documentation for your platform. This ensures you get the supported, up-to-date release and any platform-specific prerequisites.
- Official Bolt installation docs: https://puppet.com/docs/bolt/latest/bolt_installing.html

macOS options
- Preferred: follow the official installer instructions linked above.
- Homebrew may provide Bolt under different taps or names depending on your Homebrew setup. If you prefer Homebrew you can try:

```bash
# Try the simple install first (package name may vary across taps)
brew tap puppetlabs/puppet
brew install --cask puppet-bolt
```

Add `/opt/puppetlabs/bin` to your PATH in .zshrc or .bash_profile as needed:
export PATH="/opt/puppetlabs/bin:$PATH"



Windows options
- Chocolatey (recommended on Windows) or winget are common installers. Example (elevated PowerShell required for Chocolatey installs):

```powershell
# Install via Chocolatey (run in elevated PowerShell)
choco install puppet-bolt -y || choco install bolt -y || Write-Output "If choco can't find bolt, follow the official installer: https://puppet.com/docs/bolt/latest/bolt_installing.html"
# Verify
bolt --version
```

- Alternatively use winget or the official MSI installer as documented on the official docs page.

Verification

After installing Bolt, verify it is available and check the version:

```bash
bolt --version
```

Single-command (Bolt) usage

Interactive (prompts):

```bash
# Run the onboarding plan interactively (prompts for Android Studio, SDK path)
bolt plan run onboard::onboard
```

Unattended (skip interactive prompts):

```bash
# Skip interactive prompts (Android Studio GUI prompt skipped). Does not auto-install GUI.
bolt plan run onboard::onboard yes=true
```

Additional flags
- `sdk_path` - Provide an explicit Android SDK path to use for `local.properties`.
- `install_android_studio` - When true, the plan will run the Android Studio prompt task and perform install if the user consents. Default false.

Example with SDK path and install flag:

```bash
# Provide SDK path and allow Android Studio installer prompt to run
bolt plan run onboard::onboard yes=false sdk_path="/Users/me/Library/Android/sdk" install_android_studio=true
```

Running tasks directly (macOS)

```bash
# Make shell scripts executable once
chmod +x bolt/tasks/*.sh

# Run tasks step-by-step
bash bolt/tasks/install_java.sh       # installs Java 21 via Homebrew
bash bolt/tasks/install_android_cli_ndk.sh  # installs sdkmanager packages (platforms, build-tools, NDK)
bash bolt/tasks/prompt_android_studio.sh    # prompts to install Android Studio GUI (optional)
bash bolt/tasks/create_local_properties.sh  # auto-detects SDK or prompts and writes local.properties
bash bolt/tasks/init_submodules.sh          # init SSH-only submodules (requires SSH keys/agent)
bash bolt/tasks/verify_build.sh             # runs a minimal assembleDebug smoke build
```

Running tasks directly (Windows PowerShell - Run as Administrator for installs)

```powershell
# Example elevated PowerShell session
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\install_java.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\install_android_cli_ndk.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\prompt_android_studio.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\create_local_properties.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\init_submodules.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\bolt\tasks\verify_build.ps1
```

Notes

- On Windows, package installations (Chocolatey) typically require an elevated PowerShell. If you run the module plan via Bolt and the machine is not elevated, the plan will instruct you to re-run Bolt in an elevated session for the package-install steps.
- If you want, I can add exact `choco` / `brew` package names and test commands tailored to your environment; otherwise the `bolt --version` check is the most reliable verification step.

Notes
- Windows: Chocolatey is the primary package manager. The plan will instruct you to re-run in an elevated PowerShell if not run as admin.
- SSH submodules: This plan enforces SSH-only submodules. It will not create or upload SSH keys. If SSH authentication fails, follow the printed instructions to add an existing SSH key to your agent and to GitHub.
- `local.properties` will be written to the repo root and typically should not be committed.
- Logs are saved under `bolt/logs/` with timestamped filenames.

Tests

A small test script is provided at `bolt/tests/run_tests.sh` which runs non-destructive checks (executable bits, SDK detection dry-run, and DRY_RUN for submodules).

To run tests (macOS/Linux):

```bash
chmod +x bolt/tests/run_tests.sh
bash bolt/tests/run_tests.sh
```

If you want me to add CI integration or a GitHub Actions workflow for the tests, I can add that as a follow-up.

Troubleshooting: "Could not find a plan named 'onboard::onboard'"

If you installed Bolt but running `bolt plan run onboard::onboard` returns:

```
Could not find a plan named 'onboard::onboard'. For a list of available plans, run 'bolt plan show'.
```

Follow these steps:

1. Ensure you're running Bolt from the repository root (where `bolt-project.yaml` is located). The onboarding module is in `bolt/modules/onboard` and the module path is configured in `bolt-project.yaml`.

```bash
# Verify current directory contains bolt-project.yaml
ls -la bolt-project.yaml
```

2. Ensure the module has minimal metadata and the plan file is in `bolt/modules/onboard/plans/onboard.pp`. You should have `bolt/modules/onboard/metadata.json` and `bolt/modules/onboard/plans/onboard.pp`.

```bash
ls -la bolt/modules/onboard
ls -la bolt/modules/onboard/plans
```

3. List available plans to confirm Bolt sees your plan:

```bash
bolt plan show
```

4. If the plan does not appear, try running Bolt with an explicit modulepath pointing to this repo's `bolt/modules` directory:

```bash
bolt plan run onboard::onboard --modulepath $(pwd)/bolt/modules
```

5. If you're still stuck, run Bolt with debug output to get more info:

```bash
bolt plan run onboard::onboard --debug
```

If you'd like I can add a tiny `bolt` wrapper script or a Makefile target to ensure Bolt is invoked with the right modulepath automatically.

If Bolt cannot find the plan automatically

If `bolt plan run onboard::onboard` still can't find the plan, use the provided wrapper scripts which invoke Bolt with the repository's `bolt/modules` directory as the modulepath.

macOS / Linux wrapper

```bash
# Make the wrapper executable once
chmod +x ./run_onboard.sh

# Example runs
./run_onboard.sh --yes                     # unattended
./run_onboard.sh --sdk-path /path/to/sdk   # provide SDK path
./run_onboard.sh --install-android-studio  # allow Android Studio GUI prompt
```

Windows wrapper (PowerShell)

```powershell
# Example runs
.\run_onboard.ps1 -Yes
.\run_onboard.ps1 -SdkPath 'C:\Users\you\AppData\Local\Android\Sdk'
.\run_onboard.ps1 -InstallAndroidStudio
```
