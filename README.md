<div align="center">

<img width="" src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" alt="Orbot" align="center"/>

# <a href="https://orbot.app" target="_blank">Orbot</a>

### Android Onion Routing Robot

[![Weblate Status](https://hosted.weblate.org/widget/guardianproject/orbot/svg-badge.svg)](https://hosted.weblate.org/engage/guardianproject/)
[![Play Downloads](https://img.shields.io/github/downloads/guardianproject/orbot/total)](https://play.google.com/store/apps/details?id=org.torproject.android)
[![Bitrise Status](https://img.shields.io/bitrise/0e76c31b8e7e1801?token=S2weJXueO3AvrDUrrd85SA&logo=bitrise&color=blue)](https://app.bitrise.io/app/0e76c31b8e7e1801) ([CI docs](./docs/info/CI.md))

Orbot is a free VPN and proxy app that empowers other apps to use the internet more securely. Orbot uses Tor to encrypt your Internet traffic and then hides it by bouncing through a series of computers around the world. Tor is free software and an open network that helps you defend against a form of network surveillance that threatens personal freedom and privacy, confidential business activities and relationships, and state security known as traffic analysis.

***********************************************
<img src=./fastlane/metadata/android/en-US/images/phoneScreenshots/A-orbot_connected_1754319853695.png width="24%"> <img src=./fastlane/metadata/android/en-US/images/phoneScreenshots/B-choose-how_1754319851766.png width="24%">
<img src=./fastlane/metadata/android/en-US/images/phoneScreenshots/C-kindness_mode_screen_1754319855290.png width="24%"> <img src=./fastlane/metadata/android/en-US/images/phoneScreenshots/D-more_screen_1754319856820.png width="24%">

***********************************************
Orbot is a crucial component of the Guardian Project, an initiative  that leads an effort
to develop a secure and anonymous smartphone. This platform is designed for use by human rights
activists, journalists and others around the world. Learn more: <https://guardianproject.info/>

***********************************************
Tor protects your privacy on the internet by hiding the connection
between your Internet address and the services you use. We believe that Tor
is reasonably secure, but please ensure you read the usage instructions and
learn to configure it properly. Learn more: <https://torproject.org/>

***********************************************

<div align="center">
  <table>
    <tr>
      <td><a href="https://github.com/guardianproject/orbot/releases/latest">Download the Latest Orbot Release</a></td>
    </tr>
    <tr>
      <td><a href="https://support.torproject.org/faq/">Tor FAQ (Frequently Asked Questions)</a></td>
    </tr>
    <tr>
      <td><a href="https://hosted.weblate.org/engage/guardianproject/">Please Contribute Your Translations</a></td>
    </tr>
  </table>
</div>

</div>

## Development Setup

This section provides step-by-step instructions for setting up a development environment to build Orbot with Meshrabiya mesh networking integration.

### Prerequisites

Before starting, ensure you have:
- A macOS, Windows, or Linux computer
- At least 8GB of RAM and 20GB of free disk space
- Stable internet connection for downloading dependencies

### Step 1: Install Android Studio

1. **Download Android Studio**:
   - Visit [https://developer.android.com/studio](https://developer.android.com/studio)
   - Download the latest stable version for your operating system
   - Run the installer and follow the setup wizard

2. **Initial Android Studio Setup**:
   - Launch Android Studio
   - Complete the initial setup wizard
   - Accept license agreements when prompted
   - Let Android Studio download initial SDK components

### Step 2: Install Java 21

#### On macOS:
```bash
# Install Java 21 using Homebrew (recommended)
brew install openjdk@21

# Add to your shell profile (.zshrc, .bash_profile, etc.)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Verify installation
java -version
```

#### On Windows:
1. Download OpenJDK 21 from [Adoptium](https://adoptium.net/temurin/releases/?version=21)
2. Install and set `JAVA_HOME` environment variable
3. Add Java to your system PATH

#### On Linux:
```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

### Step 3: Configure Android Studio for Java 21

1. **Open Android Studio Settings**:
   - Go to `File` → `Settings` (or `Android Studio` → `Preferences` on macOS)

2. **Set Gradle JDK**:
   - Navigate to `Build, Execution, Deployment` → `Build Tools` → `Gradle`
   - Set "Gradle JDK" to Java 21
   - Click `Apply` and `OK`

### Step 4: Install Required Android SDK Components

1. **Open SDK Manager**:
   - In Android Studio, go to `Tools` → `SDK Manager`
   - Or click the SDK Manager icon in the toolbar

2. **Install SDK Platforms**:
   - Go to the "SDK Platforms" tab
   - Check and install:
     - ✅ **Android 15.0 (API 36)** - Required for compilation
     - ✅ **Android 14.0 (API 34)** - Recommended for broader compatibility
     - ✅ **Android 13.0 (API 33)** - Recommended for older device support

3. **Install SDK Tools**:
   - Go to the "SDK Tools" tab
   - Check "Show Package Details" for version-specific options
   - Install the following:
     - ✅ **Android SDK Build-Tools** (latest version)
     - ✅ **Android SDK Command-line Tools** (latest)
     - ✅ **Android SDK Platform-Tools** (latest)
     - ✅ **Android Emulator** (if you plan to use emulator)
     - ✅ **NDK (Side by side)** → Select **ndk;27.0.12077973** specifically

4. **Click "Apply"** to download and install selected components

### Step 5: Clone and Setup the Project

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/your-username/orbot-abhaya-android.git
   cd orbot-abhaya-android
   ```

2. **Create local.properties**:
   ```bash
   # Create local.properties file with your SDK location
   echo "sdk.dir=/path/to/your/Android/sdk" > local.properties
   # ex: /Users/<username>/Library/Android/sdk
   ```
   
   **SDK Location Examples** *(Update the path to match your Android SDK installation)*:
   - **macOS (default Android Studio location)**: `/Users/dreadstar/Library/Android/sdk`
   - **macOS (generic)**: `/Users/yourusername/Library/Android/sdk`
   - **Windows**: `C:\Users\yourusername\AppData\Local\Android\Sdk`
   - **Linux**: `/home/yourusername/Android/Sdk`

### Step 6: Build the Project

#### Using Visual Studio Code (Recommended for development):

1. **Install VS Code** (if not already installed):
   - Download from [https://code.visualstudio.com/](https://code.visualstudio.com/)

2. **Open Project in VS Code**:
   ```bash
   code /path/to/orbot-abhaya-android
   ```

3. **Build Debug APKs**:
   ```bash
   # Set Java environment and build
   clear && truncate -s 0 build_output.log && \
   export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
   ./gradlew assembleDebug --console=plain 2>&1 | tee build_output.log
   ```

#### Using Android Studio:

1. **Open Project**:
   - Launch Android Studio
   - Click "Open an Existing Project"
   - Navigate to and select the `orbot-abhaya-android` folder

2. **Sync Project**:
   - Android Studio will automatically sync Gradle
   - Wait for sync to complete (may take several minutes on first run)

3. **Build Project**:
   - Go to `Build` → `Make Project` or press `Ctrl+F9` (Windows/Linux) / `Cmd+F9` (macOS)
   - Or go to `Build` → `Generate Signed Bundle / APK` for release builds

### Step 7: Verify Build Success

After a successful build, check for generated APK files:

```bash
# Check generated APKs
find app/build/outputs/apk -name "*.apk" -type f

# Verify APK sizes and timestamps
ls -lh app/build/outputs/apk/fullperm/debug/
```

**Expected output** (file sizes may vary):
```
app-fullperm-arm64-v8a-debug.apk     (48M)
app-fullperm-armeabi-v7a-debug.apk   (46M)
app-fullperm-universal-debug.apk     (128M)
app-fullperm-x86-debug.apk           (46M)
app-fullperm-x86_64-debug.apk        (51M)
```

### Troubleshooting

#### Build Fails with "SDK not found":
- Ensure `local.properties` has the correct SDK path
- Verify Android SDK is properly installed

#### Java Version Issues:
```bash
# Check current Java version
java -version

# Set Java 21 for current session (macOS)
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Verify Gradle is using correct Java version
./gradlew --version
```

#### NDK Issues:
- Ensure NDK version **ndk;27.0.12077973** is installed via SDK Manager
- Check that NDK path is correctly configured in Android Studio

#### Clean Build:
If you encounter persistent issues, try a clean build:
```bash
./gradlew clean
./gradlew assembleDebug
```

### Project Structure

```
orbot-android/
├── app/                          # Main Orbot application with Meshrabiya integration
├── orbotservice/                 # Orbot service module
├── Meshrabiya/                   # Mesh networking library
│   └── lib-meshrabiya/          # Core mesh networking components
├── OrbotLib/                    # Orbot library components
├── gradle/                      # Gradle wrapper and version catalogs
└── build.gradle.kts            # Root build configuration
```

### Environment Variables Summary

For easy reference, add these to your shell profile:

```bash
# Java 21 for Android development
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Android SDK (default path for this project, adjust as needed)
export ANDROID_HOME="/Users/dreadstar/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools"
```

### Next Steps

Once the build is successful:
1. Install the APK on a device or emulator
2. Test basic Orbot functionality
3. Explore mesh networking integration features
4. Review the [KNOWLEDGE-09062025.md](./KNOWLEDGE-09062025.md) file for detailed technical information

### Getting Help

- **Build Issues**: Check [KNOWLEDGE-09062025.md](./KNOWLEDGE-09062025.md) troubleshooting section
- **Orbot Issues**: Visit [Tor Project Support](https://support.torproject.org/)
- **Android Development**: See [Android Developer Docs](https://developer.android.com/docs)

---

## Emulating

This section provides comprehensive instructions for setting up and using Android emulators to test Orbot with mesh networking functionality.

### Prerequisites

Before setting up an emulator, ensure you have completed the [Development Setup](#development-setup) section and have:
- Android Studio properly installed and configured
- Android SDK with required platforms (API 34, 35, 36)
- Java 21 configured correctly
- Project successfully built

### Step 1: Create an Android Virtual Device (AVD)

#### Using Android Studio AVD Manager:

1. **Open AVD Manager**:
   - In Android Studio, go to `Tools` → `AVD Manager`
   - Or click the AVD Manager icon in the toolbar
   - Or use the device selector dropdown and click "Device Manager"

2. **Create New Virtual Device**:
   - Click "Create Virtual Device" button
   - **Phone Category**: Select a phone device (recommended: Pixel 6, Pixel 7, or Pixel 8)
   - **System Image**: 
     - Click "Download" next to **API 35 (Android 15.0)** (recommended)
     - If API 35 has issues, use **API 34 (Android 14.0)** as fallback
     - **Architecture**: Choose `x86_64` for best performance on most computers
   - **Verify Configuration**: Review settings and click "Finish"

#### Using Command Line (Alternative):

```bash
# List available system images
avdmanager list

# Create AVD with API 35 (x86_64)
avdmanager create avd -n "Orbot_Test_API35" -k "system-images;android-35;google_apis;x86_64"

# Create AVD with API 34 (fallback)
avdmanager create avd -n "Orbot_Test_API34" -k "system-images;android-34;google_apis;x86_64"
```

### Step 2: Start the Emulator

#### From Android Studio:
1. Open AVD Manager (`Tools` → `AVD Manager`)
2. Find your created AVD and click the **Play** (▶️) button
3. Wait for the emulator to fully boot (this may take 2-5 minutes on first launch)

#### From Command Line:
```bash
# Start emulator by name
emulator -avd Orbot_Test_API35

# Start with additional options for better performance
emulator -avd Orbot_Test_API35 -memory 4096 -cores 4
```

### Step 3: Verify Emulator Setup

Once the emulator starts, verify it's working correctly:

```bash
# Check connected devices
export ANDROID_HOME="/Users/yourusername/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
adb devices

# Expected output:
# List of devices attached
# emulator-5554   device
```

**Verify boot completion**:
```bash
# Wait for emulator to fully boot
adb wait-for-device
adb shell getprop sys.boot_completed

# Should output: 1 (when fully booted)
```

### Step 4: Install and Test Orbot

#### Install the APK:

```bash
# Install the debug APK (choose appropriate architecture)
adb install -r app/build/outputs/apk/fullperm/debug/app-fullperm-x86_64-debug.apk

# Verify installation
adb shell pm list packages | grep torproject
# Expected output: package:org.torproject.android.debug
```

#### Launch Orbot:

```bash
# Launch Orbot main activity
adb shell am start -n org.torproject.android.debug/org.torproject.android.OrbotActivity

# Check app launch in logcat
adb logcat -s "org.torproject.android.debug:*" | head -10
```

### Step 5: Testing Mesh Functionality

#### Navigate to Mesh Tab:
1. **Open Orbot** on the emulator
2. **Navigate to Mesh tab** in the bottom navigation
3. **Verify Enhanced Mesh UI** loads with:
   - Network Overview card with statistics
   - Tor Gateway service card with toggle
   - Internet Gateway service card
   - Network control buttons (Start/Stop Mesh)
   - Network status information

#### Test Mesh Controls:
1. **Toggle Gateway Services**: Try enabling/disabling Tor and Internet gateway toggles
2. **Start/Stop Mesh**: Use the network control buttons
3. **Refresh Status**: Test the refresh button functionality
4. **Check Real-time Updates**: Observe if the UI updates automatically every 5 seconds

### Troubleshooting Emulator Issues

#### Emulator Won't Start or Boot:

**Check Hardware Acceleration**:
```bash
# On macOS, verify Intel HAXM or Apple Silicon support
system_profiler SPHardwareDataType

# Ensure virtualization is enabled in BIOS/UEFI (Windows/Linux)
```

**Try Different System Images**:
- If API 36 fails, try API 35 or API 34
- Switch between `x86_64` and `x86` architectures if needed
- Try images without Google APIs if standard images fail

**Increase Emulator Resources**:
```bash
# Start with more memory and CPU cores
emulator -avd Orbot_Test_API35 -memory 6144 -cores 6
```

#### Installation Issues:

**APK Installation Fails**:
```bash
# Clear app data and try fresh install
adb shell pm clear org.torproject.android.debug
adb uninstall org.torproject.android.debug
adb install app/build/outputs/apk/fullperm/debug/app-fullperm-x86_64-debug.apk
```

**"Broken pipe" or Connection Issues**:
```bash
# Restart ADB server
adb kill-server
adb start-server
adb devices
```

#### App Crashes or Issues:

**Monitor App Runtime**:
```bash
# Monitor for crashes and errors
adb logcat -s "AndroidRuntime:E" -s "org.torproject.android.debug:*"

# Clear logcat and monitor fresh launch
adb logcat -c
adb shell am start -n org.torproject.android.debug/org.torproject.android.OrbotActivity
adb logcat -s "org.torproject.android.debug:*"
```

**Check App Permissions**:
```bash
# Grant necessary permissions
adb shell pm grant org.torproject.android.debug android.permission.INTERNET
adb shell pm grant org.torproject.android.debug android.permission.ACCESS_NETWORK_STATE
```

#### UI Issues:

**Mesh Fragment Not Loading Enhanced UI**:
1. **Verify Navigation**: Ensure `nav_graph.xml` points to `EnhancedMeshFragment`
2. **Check Layout**: Confirm `fragment_mesh_enhanced.xml` exists and is referenced correctly
3. **Rebuild Project**: Clean and rebuild if layout changes aren't reflected
4. **Reinstall APK**: Force reinstall with `-r` flag

**Material3 Theme Issues**:
```bash
# Check for theme-related crashes in logcat
adb logcat | grep -i "material\|theme\|inflate"
```

### Performance Optimization

#### For Better Emulator Performance:

1. **Enable Hardware Acceleration**:
   - Ensure Intel HAXM (Intel) or Hypervisor Framework (Apple Silicon) is installed
   - Use x86_64 system images when possible

2. **Allocate Sufficient Resources**:
   ```bash
   # Recommended emulator settings
   emulator -avd Orbot_Test_API35 \
     -memory 4096 \
     -cores 4 \
     -gpu host \
     -skin 1080x1920
   ```

3. **Disable Unnecessary Features**:
   - Turn off animations in emulator settings
   - Disable location services if not needed
   - Close other resource-intensive applications

#### Emulator vs Physical Device:

**Emulator Advantages**:
- ✅ Consistent test environment
- ✅ Easy to reset and reproduce issues
- ✅ Debug-friendly with full access
- ✅ Multiple API levels without multiple devices

**Physical Device Advantages**:
- ✅ Real-world performance testing
- ✅ Actual hardware sensors and capabilities
- ✅ True network connectivity testing
- ✅ Better for mesh networking validation

### Testing Screenshots and Evidence

#### Capture Screenshots for Documentation:
```bash
# Take screenshot of current emulator screen
adb exec-out screencap -p > orbot_mesh_screenshot_$(date +%Y%m%d_%H%M%S).png

# Record screen video (Android 4.4+)
adb shell screenrecord /sdcard/orbot_demo.mp4
# Stop recording with Ctrl+C, then pull the file:
adb pull /sdcard/orbot_demo.mp4
```

#### Document Test Results:
- **Take screenshots** of each major UI screen (Connect, Mesh, More, Kindness)
- **Record interactions** showing mesh functionality
- **Log any issues** with specific error messages from logcat
- **Note performance** observations and device specifications

### Emulator Environment Summary

For consistent testing, use these environment variables:

```bash
# Android SDK and tools
export ANDROID_HOME="/Users/yourusername/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

# Java 21 for building
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Quick emulator launch function (add to ~/.zshrc or ~/.bashrc)
alias start-orbot-emulator="emulator -avd Orbot_Test_API35 -memory 4096 -cores 4 &"

# Quick install and launch function
alias install-orbot="adb install -r app/build/outputs/apk/fullperm/debug/app-fullperm-x86_64-debug.apk && adb shell am start -n org.torproject.android.debug/org.torproject.android.OrbotActivity"
```

### Next Steps After Emulator Setup

1. **Test Core Functionality**: Verify basic Orbot connection and proxy functionality
2. **Explore Mesh Features**: Test all mesh networking controls and status displays
3. **Performance Testing**: Monitor resource usage and responsiveness
4. **Integration Testing**: Test interaction between Orbot and mesh networking components
5. **Documentation**: Record test results and any issues discovered

---

## Testing

This section provides instructions for running comprehensive tests and generating code coverage reports.

### Running All Tests with Coverage

The project includes an automated `runAllTests` task that executes unit tests across all modules and generates comprehensive coverage reports.

#### Quick Test Execution

```bash
# Run all tests with coverage calculation and output logging
truncate -s 0 runAllTests_output.log && \
export JAVA_HOME=$(/usr/libexec/java_home -v 21) && \
./gradlew runAllTests --console=plain 2>&1 | tee runAllTests_output.log
```

This command will:
1. **Clear the log file** (`truncate -s 0 runAllTests_output.log`)
2. **Set Java 21 environment** (`export JAVA_HOME=...`)
3. **Execute all tests** with fresh builds (no caching)
4. **Generate coverage reports** for each module
5. **Create aggregated coverage report** combining all modules
6. **Calculate coverage percentages** automatically
7. **Log all output** to `runAllTests_output.log`

#### What the runAllTests Task Does

- **Cleans build outputs** to ensure fresh test runs
- **Runs unit tests** across all modules:
  - `app` module (main Orbot application)
  - `orbotservice` module (Orbot service components)
  - `Meshrabiya:lib-meshrabiya` module (mesh networking library)
- **Generates individual Jacoco coverage reports** for each module
- **Creates aggregated coverage report** combining all modules
- **Automatically calculates and displays coverage percentages**

#### Test Results and Coverage Reports

After successful execution, you'll find:

**Coverage Summary** (automatically calculated):
```
=== PROJECT COVERAGE SUMMARY ===
Instructions: 23.16% (11525 covered / 49773 total)
Branches: 11.32% (365 covered / 3225 total)
Lines: 24.68% (2112 covered / 8556 total)
Complexity: 15.31% (565 covered / 3691 total)
Methods: 23.67% (488 covered / 2062 total)
```

**Generated Report Files**:
- `coverage_summary.log` - Coverage percentages summary
- `runAllTests_output.log` - Complete test execution log
- `build/reports/jacoco/aggregated/` - Combined coverage reports (HTML, XML, CSV)
- Individual module reports in each module's `build/reports/jacoco/` directory

**HTML Coverage Reports** (open in browser):
- **Aggregated**: `build/reports/jacoco/aggregated/html/index.html`
- **App Module**: `app/build/reports/jacoco/jacocoTestReport/html/index.html`
- **OrbotService**: `orbotservice/build/reports/jacoco/jacocoTestReport/html/index.html`
- **Meshrabiya**: `Meshrabiya/lib-meshrabiya/build/reports/jacoco/jacocoTestReport/html/index.html`

#### Checking Test Results

```bash
# View coverage summary
cat coverage_summary.log

# Check test execution log (last 20 lines)
tail -20 runAllTests_output.log

# Verify all generated coverage reports exist
ls -la build/reports/jacoco/aggregated/
```

#### Individual Test Execution

If you need to run tests for specific modules:

```bash
# App module tests only
./gradlew :app:testFullpermDebugUnitTest

# OrbotService module tests only
./gradlew :orbotservice:testDebugUnitTest

# Meshrabiya module tests only
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest

# Generate coverage report for specific module
./gradlew :app:jacocoTestReport
```

#### Troubleshooting Tests

**Test failures**:
- Check `runAllTests_output.log` for detailed error messages
- Ensure all dependencies are properly installed
- Verify Java 21 is being used: `./gradlew --version`

**Coverage report generation issues**:
- Ensure tests completed successfully before coverage calculation
- Check that `build/reports/jacoco/aggregated/jacoco.csv` exists
- Verify `calculate_coverage.sh` script has execute permissions: `chmod +x calculate_coverage.sh`

**Environment issues**:
```bash
# Verify Java version
java -version

# Check Gradle wrapper
./gradlew --version

# Clean and retry
./gradlew clean
```

---

## AIDL distribution (meshrabiya-api -> consumers)

The canonical Meshrabiya AIDL files live in the `meshrabiya-api` module and are the single source-of-truth:

```
meshrabiya-api/src/main/aidl/com/ustadmobile/meshrabiya/api/
```

To make these AIDL definitions available to consumer modules at compile time we run a distribution task that copies the canonical AIDL into each consumer's generated directory:

```
<consumer>/build/generated/meshrabiya-aidl/<variant>/com/ustadmobile/meshrabiya/api/
```

DO NOT commit the generated copies into consumer source trees. Instead add the generated directory to each consumer's AIDL source set so the compiler can find the files during build.

Example (add to the consumer module `build.gradle.kts`, e.g. `abhaya-sensor-android/app/build.gradle.kts`):

```kotlin
// Place this near the bottom of the consumer module Gradle file
afterEvaluate {
   listOf("debug", "release").forEach { variant ->
      val genDir = file("build/generated/meshrabiya-aidl/$variant")
      // Attach to the variant sourceSet if it exists, otherwise attach to main
      android.sourceSets.findByName(variant)?.aidl?.srcDir(genDir)
         ?: android.sourceSets.getByName("main").aidl.srcDir(genDir)
   }
}
```

Suggested `.gitignore` entries for consumer modules so generated AIDL are not committed:

```
# Ignore generated Meshrabiya AIDL distributed into consumer modules
/abhaya-sensor-android/app/src/main/aidl/com/ustadmobile/meshrabiya/api/
/orbotservice/src/main/aidl/com/ustadmobile/meshrabiya/api/
```

If Meshrabiya AIDL files were already committed into a consumer module, remove them from the index (keeps history):

```bash
git rm --cached -r abhaya-sensor-android/app/src/main/aidl/com/ustadmobile/meshrabiya/api
git commit -m "Remove generated meshrabiya AIDL from consumer; use generated copy instead"
```

The repository contains a task (`meshrabiya-api:distributeAidlToConsumers`) which is wired to run before consumer assemble tasks so the generated files are available during compilation.

## Meshrabiya: test-mode flags, socket timeouts, and running tests

This project includes the Meshrabiya mesh networking library and a set of test helpers and runtime flags to make unit/integration tests deterministic and visible. When working on Meshrabiya features or tests, the notes below will help you run reliable tests and avoid hangs caused by blocking socket calls and background schedulers.

### Java version

Meshrabiya modules target Java 21. Make sure your shell and Gradle use a Java 21 JDK when running builds and tests. Example (macOS):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
echo "JAVA_HOME=$JAVA_HOME"
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest --no-daemon -Dtest.single=YourTestClass -Dmeshrabiya.hardware.testMode=true -i
```

Replace `YourTestClass` with the test you want to run.

### Runtime flags / system properties

Two system properties are used by the Meshrabiya library to alter behavior for tests:

- `-Dmeshrabiya.hardware.testMode=true`
   - When enabled, a number of long-running or blocking operations use short timeouts and "quick-paths" to avoid hanging tests. Examples: socket accept/read timeouts are reduced, device capability collection returns a minimal snapshot, and periodic background tasks can be skipped.
- `-Dmeshrabiya.enableOriginatingPeriodicTasks=false`
   - Disable periodic originating background tasks that would normally run in production. Useful to make unit tests deterministic.

Prefer passing these system properties to Gradle when running tests locally or in CI. Example:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest --no-daemon -Dmeshrabiya.hardware.testMode=true -Dmeshrabiya.enableOriginatingPeriodicTasks=false
```

### Socket timeouts (SocketTimeoutsProvider)

To avoid test hangs caused by blocking `ServerSocket.accept()` and socket read calls, Meshrabiya introduces a small provider abstraction named `SocketTimeoutsProvider` (implemented in the Meshrabiya library). The defaults are:

- Test mode (when `meshrabiya.hardware.testMode=true`):
   - accept timeout: 2000 ms
   - socket SO_TIMEOUT (read timeout): 2000 ms
- Production (default, when testMode is not set):
   - accept timeout: 0 ms (no timeout)
   - socket SO_TIMEOUT: 0 ms (no timeout)

Code that accepts sockets (`ChainSocketServer`) and code that receives datagrams (`VirtualNodeDatagramSocket`) read these values from the provider and only set timeouts when the configured value is > 0. This keeps production behavior unchanged while preventing unit tests from blocking indefinitely.

If you write or update unit tests that instantiate `ChainSocketServer` or `VirtualNodeDatagramSocket` directly, prefer injecting a `TestSocketTimeoutsProvider` instance with explicit, deterministic values rather than relying on global system properties. This avoids flakiness when tests run in different environments.

### Example: run a single test with verbose logs

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :Meshrabiya:lib-meshrabiya:testDebugUnitTest --no-daemon -Dtest.single=MeshrabiyaAidlServiceEndToEndStreamTest -Dmeshrabiya.hardware.testMode=true -Dmeshrabiya.enableOriginatingPeriodicTasks=false -i
```

### Notes and best practices

- Prefer injecting `TestSocketTimeoutsProvider` into unit tests where possible. The provider lives under `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/net/SocketTimeoutsProvider.kt`.
- Avoid long-running background schedulers in unit tests; use `-Dmeshrabiya.enableOriginatingPeriodicTasks=false` or construct the managers with a test-mode flag when supported.
- Ensure CI runners use Java 21 for Meshrabiya tests to match `kotlinOptions.jvmTarget = "21"`.
- If you need production-like socket behavior in integration tests, pass a provider that sets longer timeouts (or leave `testMode=false`) and make sure tests are resilient to longer waits.

If you'd like, I can add an explicit CI job example that runs the Meshrabiya module tests with Java 21 and the test-mode system properties set.

### Changing production settings

If you need to change Meshrabiya's behavior in production (for example to enable socket timeouts, metric collection, or to flip feature flags), prefer a controlled approach rather than relying on system properties alone. Recommended options:

- Build-time flags (BuildConfig)
   - Add a `buildConfigField` in the module `build.gradle.kts` (or a gradle flavor) to expose the flag at runtime in a typesafe way. Example:

   ```kotlin
   android {
      defaultConfig {
         buildConfigField("boolean", "MESHRABIYA_ENABLE_SOCKET_TIMEOUTS", "false")
      }
   }
   ```

   In code use `BuildConfig.MESHRABIYA_ENABLE_SOCKET_TIMEOUTS` to branch behavior.

- Runtime configuration (SharedPreferences or remote config)
   - If you want the ability to flip behavior at runtime without a new build, read a setting from `SharedPreferences` or a remote config service (Firebase Remote Config, your own server). Document the preference key and defaults and add a secure admin path for toggling in production.

- Inject a custom `SocketTimeoutsProvider`
   - For production rollout you can instantiate and inject a provider that returns non-zero timeouts. Prefer dependency-injection (Dagger/Hilt or a constructor parameter) for the provider so tests can override it with `TestSocketTimeoutsProvider`.

   Example (Kotlin):

   ```kotlin
   val provider: SocketTimeoutsProvider = if (BuildConfig.MESHRABIYA_ENABLE_SOCKET_TIMEOUTS) {
      // production provider with sensible timeouts
      object : SocketTimeoutsProvider {
         override val acceptTimeoutMillis: Int = 30000 // 30s
         override val socketSoTimeoutMillis: Int = 30000 // 30s
      }
   } else DefaultSocketTimeoutsProvider()

   ChainSocketServer(socketTimeoutsProvider = provider)
   ```

- Metrics and monitoring
   - If you enable socket timeouts in production, add logging and metrics around accept/read timeouts and socket errors so you can observe the impact. Emit counts and histograms for timeout occurrences and failed connections.

- Rollout strategy
   - Start with a small percentage rollout (via remote config or staged build) and monitor metrics for connection errors and user-facing issues.
   - Provide a fast rollback path (remote config flip or a quick patch) in case timeouts cause regressions.

Following these patterns keeps the production behavior explicit and testable, while allowing safe experimentation and quick rollbacks if needed.


***********************************************
**Copyright &#169; 2009-2025, Nathan Freitas, The Guardian Project**

---

