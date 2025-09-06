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

***********************************************
**Copyright &#169; 2009-2025, Nathan Freitas, The Guardian Project**

---

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
