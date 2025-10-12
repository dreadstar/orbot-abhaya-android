# KNOWLEDGE - October 10, 2025

## Abhaya Sensor Android App - UI Refactor & Integration

### Project Context
- **Location**: `/Users/dreadstar/workspace/orbot-android/abhaya-sensor-android/`
- **Main UI File**: `app/src/main/java/com/ustadmobile/meshrabiya/sensor/ui/SensorApp.kt`
- **Device**: Vivo (ARMv7a, ID: 30870044490006E)
- **Build Tool**: Gradle 9.0.0 with Kotlin DSL
- **Java Version**: Java 21 (Temurin JDK)
- **Build Variant**: `fullperm` (full permissions variant)

---

## UI Refactor Summary

### Objective
Modernize the Abhaya Sensor App UI to match a TSX design specification while maintaining full backend integration with HttpStreamIngestor, SensorManager, CameraCapture, and AudioCapture controllers.

### Approach Taken
**Clean Integration Strategy** (successful after multiple failed attempts):
1. ✅ Created clean UI base with modern design (gradients, glassmorphic cards)
2. ✅ Validated syntax with Kotlin compilation
3. ✅ Integrated HttpStreamIngestor backend piece-by-piece
4. ✅ Integrated SensorManager hardware sensors with dynamic registration
5. ✅ Integrated CameraCapture with live preview
6. ✅ Integrated AudioCapture with automatic lifecycle
7. ✅ Added real-time event logging
8. ✅ Implemented disabled state for unavailable sensors

### Key UI Features Implemented

#### 1. **Modern Visual Design**
- **Gradient Background**: Slate-900 → Purple-900 → Slate-900 vertical gradient
- **Glassmorphic Cards**: Semi-transparent white overlay (10% opacity) with rounded corners
- **Color Scheme**: 
  - Primary: Purple (#8B5CF6)
  - Success: Green (#10B981)
  - Error: Red (#EF4444)
  - Gray tones for disabled states

#### 2. **App Header with Status Indicator**
- Large "Abhaya Sensor" title (28sp, bold, white)
- Animated pulse indicator for running state
- Status badge: "ACTIVE" (green) / "STOPPED" (gray)
- Dynamic status message below header

#### 3. **Camera Configuration Panel**
- Live camera preview (200dp height, black background)
- Automatically starts/stops based on sensor selection
- Uses AndroidView with PreviewView integration
- Connected to CameraCapture controller

#### 4. **Audio Configuration Panel**
- Placeholder for future audio visualization
- Connected to AudioCapture controller
- Starts/stops automatically with sensor selection

#### 5. **Polling Frequency Selector**
- 6 frequency options: 1, 5, 10, 25, 50, 100 Hz
- Grid layout (3x2)
- Purple highlight for selected frequency
- Default: 10 Hz

#### 6. **Sensor Selection with Categories**
- **4 Collapsible Categories**:
  1. Motion Sensors (Accelerometer, Gyroscope, Magnetometer, Linear Acceleration)
  2. Environmental (Temperature, Pressure, Humidity, Light)
  3. Camera & Audio (Camera Stream, Audio Stream, Periodic Photos)
  4. System Metrics (Heart Beat, Step Counter, Proximity)

- **Smart Availability Handling**:
  - ✅ Available sensors: White text, enabled checkbox, original description
  - 🚫 Unavailable sensors: Gray text (50% opacity), disabled checkbox (30% opacity), "Not available on this device" description, "N/A" status badge
  - Unavailable sensors have subtle dark background overlay

- **Visual Indicators**:
  - Checkbox colors: Purple (checked), white/40% (unchecked), gray/30% (disabled)
  - Status badges: "ON" (green), "OFF" (gray), "N/A" (light gray)

#### 7. **Event Log**
- Shows last 200 events in monospace font
- Format: `{streamId} @ {timestampMs} ({payloadLength} bytes)`
- Scrollable 150dp height container
- Only visible when events exist
- Real-time updates from ingestor event flow

#### 8. **Control Buttons**
- Large prominent button (56dp height, 16dp rounded corners)
- Color changes: Green ("Start Streaming") / Red ("Stop Streaming")
- Triggers start/stop functions connected to backend

---

## Backend Integration Architecture

### HttpStreamIngestor Integration
```kotlin
val ingestor = remember {
    val http = HttpStreamIngestor("https://example.com/store", token = null)
    val _events = MutableSharedFlow<UIIngestor.UIEvent>(replay = 0, extraBufferCapacity = 100)
    object : UIIngestor {
        override val events: SharedFlow<UIIngestor.UIEvent> = _events
        override fun start() = http.start()
        override fun stop() = http.stop()
        override fun ingestSensorReading(streamId: String, timestampMs: Long, payload: ByteArray) {
            http.ingestSensorReading(streamId, timestampMs, payload)
            try {
                _events.tryEmit(UIIngestor.UIEvent(streamId, timestampMs, payload.size))
            } catch (_: Throwable) {}
        }
    }
}
```

### Hardware Sensor Discovery
```kotlin
val hardwareSensors = remember { mutableStateMapOf<String, Sensor>() }
LaunchedEffect(Unit) {
    sensorManager.getSensorList(Sensor.TYPE_ALL).forEach { s ->
        val id = s.stringType ?: "sensor_${s.type}"
        hardwareSensors[id] = s
    }
}
```

### Dynamic Sensor Registration
```kotlin
LaunchedEffect(selectedSensors, hardwareSensors.keys.toList()) {
    val hwIds = hardwareSensors.keys
    val toRegister = selectedSensors.filter { it in hwIds } - listeners.keys
    val toUnregister = listeners.keys - selectedSensors

    // Unregister removed sensors
    toUnregister.forEach { id ->
        listeners[id]?.let { sensorManager.unregisterListener(it) }
        listeners.remove(id)
    }

    // Register new sensors
    toRegister.forEach { id ->
        val sensor = hardwareSensors[id] ?: return@forEach
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return
                try {
                    val payload = floatsToByteArray(event.values)
                    val ts = System.currentTimeMillis()
                    ingestor.ingestSensorReading(id, ts, payload)
                } catch (_: Exception) {}
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listeners[id] = listener
    }
}
```

### Camera & Audio Lifecycle
```kotlin
val cameraController = remember { CameraCapture(context, lifecycleOwner, ingestor) }
val audioController = remember { AudioCapture(ingestor) }

LaunchedEffect(selectedSensors) {
    if (selectedSensors.contains("camera_stream")) {
        cameraController.start(periodicSeconds = 5)
    } else {
        cameraController.stop()
    }

    if (selectedSensors.contains("audio_stream")) {
        audioController.start()
    } else {
        audioController.stop()
    }
}
```

### Cleanup on Dispose
```kotlin
DisposableEffect(Unit) {
    onDispose {
        listeners.values.forEach { sensorManager.unregisterListener(it) }
        listeners.clear()
        eventsJob?.cancel()
        if (ingestorRunning) {
            ingestor.stop()
        }
    }
}
```

---

## Standard Build Commands

### RULE: Always Build from Project Root
**PROJECT ROOT**: `/Users/dreadstar/workspace/orbot-android`  
**NEVER** build from `abhaya-sensor-android` subdirectory (causes meshrabiya-api path errors)

### Standard Command Format
All commands follow this pattern for consistency and logging:
```bash
: > /path/to/logfile.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew [task] 2>&1 | tee /path/to/logfile.log
```

### Build Commands

#### 1. **Compile Kotlin Only** (Fast validation)
```bash
: > /Users/dreadstar/workspace/orbot-android/kotlin_compile.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:compileFullpermDebugKotlin 2>&1 | tee kotlin_compile.log
```
**Purpose**: Quick syntax validation without full APK build  
**Time**: ~30-45 seconds

#### 2. **Build Full APK** (Complete build)
```bash
: > /Users/dreadstar/workspace/orbot-android/full_apk_build.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:assembleFullpermDebug 2>&1 | tee full_apk_build.log
```
**Purpose**: Creates deployable APK  
**Output**: `abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-debug.apk`  
**Time**: ~2-3 minutes

#### 3. **Clean Build** (Nuclear option)
```bash
: > /Users/dreadstar/workspace/orbot-android/clean_build.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew clean :abhaya-sensor-android:app:assembleFullpermDebug 2>&1 | tee clean_build.log
```
**Purpose**: Full clean and rebuild (fixes Gradle cache issues)  
**Time**: ~3-5 minutes

### Deployment Commands

#### 4. **Install APK to Device**
```bash
adb -s 30870044490006E install -r /Users/dreadstar/workspace/orbot-android/abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-debug.apk
```
**Flags**:
- `-s 30870044490006E`: Target specific device
- `-r`: Replace existing app (keeps data)

#### 5. **Build and Install in One Command**
```bash
: > /Users/dreadstar/workspace/orbot-android/build_install.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:assembleFullpermDebug 2>&1 | tee build_install.log && \
adb -s 30870044490006E install -r abhaya-sensor-android/app/build/outputs/apk/fullperm/debug/app-fullperm-debug.apk
```

### Testing Commands

#### 6. **Build Test APK**
```bash
: > /Users/dreadstar/workspace/orbot-android/test_build.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:assembleFullpermDebugAndroidTest 2>&1 | tee test_build.log
```

#### 7. **Run All Tests on Device**
```bash
: > /Users/dreadstar/workspace/orbot-android/run_tests.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:connectedFullpermDebugAndroidTest 2>&1 | tee run_tests.log
```
**Output**: Test results in `abhaya-sensor-android/app/build/reports/androidTests/connected/`

#### 8. **Run Specific Test Class**
```bash
: > /Users/dreadstar/workspace/orbot-android/specific_test.log && \
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" && \
cd /Users/dreadstar/workspace/orbot-android && \
./gradlew :abhaya-sensor-android:app:connectedFullpermDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ustadmobile.meshrabiya.sensor.SensorAppComposeTest \
  2>&1 | tee specific_test.log
```

### Logcat Commands

#### 9. **View Live Logcat for Sensor App**
```bash
adb -s 30870044490006E logcat -c && \
adb -s 30870044490006E logcat | grep -E "Abhaya|Sensor|Ingestor|Camera|Audio"
```
**Purpose**: Clear logs and monitor app-specific output

#### 10. **Save Logcat to File**
```bash
adb -s 30870044490006E logcat -d > /Users/dreadstar/workspace/orbot-android/sensor_app_logcat.log
```
**Purpose**: Dump current logcat buffer to file for analysis

#### 11. **Monitor Specific Tag**
```bash
adb -s 30870044490006E logcat | grep "SensorApp"
```

#### 12. **Filter by Priority (Error + Fatal)**
```bash
adb -s 30870044490006E logcat "*:E"
```

### Device Management Commands

#### 13. **List Connected Devices**
```bash
adb devices -l
```

#### 14. **Check Device Info**
```bash
adb -s 30870044490006E shell getprop | grep -E "model|manufacturer|version"
```

#### 15. **Uninstall App**
```bash
adb -s 30870044490006E uninstall com.ustadmobile.meshrabiya.sensor
```

#### 16. **Launch App via ADB**
```bash
adb -s 30870044490006E shell am start -n com.ustadmobile.meshrabiya.sensor/.MainActivity
```

---

## Build Error Patterns & Solutions

### Error: "Project with path ':meshrabiya-api' could not be found"
**Cause**: Building from `abhaya-sensor-android` subdirectory instead of project root  
**Solution**: Always build from `/Users/dreadstar/workspace/orbot-android`

### Error: "No value passed for parameter 'token'"
**Cause**: HttpStreamIngestor constructor signature changed  
**Solution**: Pass `token = null` as second parameter:
```kotlin
HttpStreamIngestor("https://example.com/store", token = null)
```

### Error: Compilation succeeds but tests fail to deploy
**Cause**: Gradle cache not updated with test fixes  
**Solution**: Run clean build:
```bash
./gradlew clean :abhaya-sensor-android:app:assembleFullpermDebugAndroidTest
```

---

## File Backup Strategy

### Active Files
- `SensorApp.kt` - Current working UI (642 lines)

### Backup Files
- `SensorApp.kt.bak` - Original working version (389 lines) - **KEEP for reference**
- `SensorAppNew.kt.bak` - Modern UI reference (1199 lines) - **KEEP for TSX design patterns**
- `SensorApp_NEW_COMPLETE.kt.bak` - Broken attempt - **Can delete**

### Rule
Always create `.bak` before major refactors. Never directly edit files without validation strategy.

---

## Testing Status

### Current Test Suite
- **Total Tests**: 81
- **Pass Rate (Round 3)**: 43% (35 passing, 46 failing)

### Test Categories
1. **Camera Tests**: 14 failures (runOnMainSync wrappers needed - FIXED in source but not deployed)
2. **Compose UI Tests**: 16 failures (createAndroidComposeRule needed - FIXED in source but not deployed)
3. **Lifecycle Tests**: 9 failures (void return fixes - DEPLOYED, working)
4. **AIDL Tests**: 5 failures (void return fixes - DEPLOYED, working)
5. **Permissions Tests**: 3 failures
6. **Data Persistence**: 7 tests passing ✅

### Known Issues
Camera and Compose test fixes exist in source code but don't deploy after regular builds. Requires clean rebuild to deploy test APK with fixes.

---

## Key Learnings

### What Worked
1. ✅ **Incremental integration with validation** at each step
2. ✅ **Clean slate approach** - copy clean UI, then add backend piece-by-piece
3. ✅ **Build after every change** - catch errors immediately
4. ✅ **Standard command format** - consistent logging and error tracking
5. ✅ **Always build from project root** - avoid path issues

### What Failed
1. ❌ Creating standalone decorative UIs without integration
2. ❌ Large wholesale replacements without validation
3. ❌ Building from subdirectories
4. ❌ Making bracket changes without careful verification
5. ❌ Assuming test fixes deploy automatically (Gradle caching issue)

### Best Practices
1. Create `.bak` files before major changes
2. Use Kotlin compilation for fast validation
3. Check full build logs, not just tail output
4. Grep for specific error patterns: `grep -E "^e:|error:"`
5. Clean build when cache issues suspected
6. Always verify imports match project dependencies (no accompanist, use Material not Material3)

---

## Next Steps

1. ✅ Build full production APK
2. ✅ Deploy to Vivo device
3. ⏳ Test all functionality end-to-end:
   - Sensor discovery and selection
   - Hardware sensor streaming
   - Camera preview and capture
   - Audio streaming
   - Event log display
   - Start/Stop controls
4. ⏳ Run clean test build and execute Round 4 tests
5. ⏳ Target 80%+ test pass rate (65+ tests passing)

---

## Important Context

### Project Structure
```
orbot-android/                          # PROJECT ROOT - BUILD FROM HERE
├── abhaya-sensor-android/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/java/com/ustadmobile/meshrabiya/sensor/
│   │       ├── MainActivity.kt
│   │       ├── ui/
│   │       │   └── SensorApp.kt       # Main UI file (642 lines)
│   │       ├── capture/
│   │       │   ├── CameraCapture.kt
│   │       │   └── AudioCapture.kt
│   │       └── stream/
│   │           └── HttpStreamIngestor.kt
│   └── build.gradle.kts
├── meshrabiya-api/                     # Dependency subproject
└── build.gradle.kts
```

### Dependencies in Use
- Jetpack Compose (Material, NOT Material3)
- CameraX (camera.core, camera.lifecycle, camera.view)
- Kotlin Coroutines (flows, jobs)
- Android SensorManager (hardware sensors)
- AndroidX Lifecycle
- No Accompanist (removed - not in dependencies)

---

## UI Component Hierarchy

```
SensorApp()
├── Box (gradient background)
│   └── Column (main scrollable container)
│       ├── AppHeader (status, animated pulse)
│       ├── Camera Configuration Card
│       │   └── AndroidView(PreviewView)
│       ├── Audio Configuration Card (placeholder)
│       ├── Polling Frequency Section
│       │   └── 6 frequency buttons in 3x2 grid
│       ├── Sensors Selection Card
│       │   └── For each category:
│       │       ├── Category header (collapsible)
│       │       └── For each sensor:
│       │           ├── Checkbox (enabled/disabled)
│       │           ├── Name & Description
│       │           └── Status badge (ON/OFF/N/A)
│       ├── Event Log Card (conditional)
│       │   └── Scrollable log entries
│       └── Control Buttons (Start/Stop)
```

---

## References

- Original working UI: `SensorApp.kt.bak` (389 lines)
- TSX design reference: `SensorAppNew.kt.bak` (1199 lines)
- Test files: `abhaya-sensor-android/app/src/androidTest/java/com/ustadmobile/meshrabiya/sensor/`
- Build logs: `/Users/dreadstar/workspace/orbot-android/*.log`

---

**Document Created**: October 10, 2025  
**Status**: Sensor App UI fully integrated, ready for device testing  
**Next Session**: Deploy APK, run end-to-end tests, execute test suite Round 4
