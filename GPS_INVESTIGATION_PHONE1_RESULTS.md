# GPS Investigation Results - Phone 1 (30870044490006E)
**Date:** March 2, 2026  
**Investigator:** AI Agent  
**Status:** IN PROGRESS

## EXECUTIVE SUMMARY

**✅ PHASE 1 COMPLETE: System GPS is functional and enabled**  
**✅ PHASE 2 COMPLETE: App permissions are granted**  
**⚠️ CRITICAL FINDING: App requests GPS with BALANCED mode (not HIGH_ACCURACY)**

---

## PHASE 1: BASELINE SYSTEM STATE ✅

### Step 1.1: Location Services Status
```bash
$ adb -s 30870044490006E shell settings get secure location_mode
3
```
**Result:** ✅ PASS - Location mode = 3 (High Accuracy mode enabled)  
**Interpretation:** System-wide location services are enabled with GPS + WiFi + Mobile networks.

---

### Step 1.2: GPS Provider Status
```bash
$ adb -s 30870044490006E shell dumpsys location | grep -i "gps"
```
**Key Findings:**
- ✅ GPS provider exists and is operational
- ✅ GPS provider accepts registration requests
- ✅ GPS provider responds to location requests
- ⚠️ **CRITICAL:** Orbot app requests location with **BALANCED** mode (not HIGH_ACCURACY)
- ✅ Google Maps successfully requests GPS with HIGH_ACCURACY mode

**Example Orbot GPS Request:**
```
03-02 03:54:56.274: gps provider +registration 10374/org.torproject.android.debug/BAE57B35 
  -> Request[@0 BALANCED, duration=+30s0ms, maxUpdates=1, WorkSource{10374 org.torproject.android.debug}]
```

**Example Google Maps Request (for comparison):**
```
03-02 03:55:35.980: gps provider +registration 10180/com.google.android.apps.maps/0362E53E 
  -> Request[@+997ms HIGH_ACCURACY, WorkSource{10180 com.google.android.apps.maps}]
```

**⚠️ POTENTIAL ROOT CAUSE #1:**  
Orbot is requesting location with **BALANCED** priority instead of **HIGH_ACCURACY**. BALANCED mode may use WiFi/cell towers instead of GPS, which could explain why GPS location is not acquired outdoors.

---

## PHASE 2: APP-LEVEL PERMISSIONS ✅

### Step 2.1: Runtime Permissions
```bash
$ adb -s 30870044490006E shell dumpsys package org.torproject.android.debug | grep -A 50 "runtime permissions:"
```

**Results:**
- ✅ `ACCESS_FINE_LOCATION: granted=true, flags=[USER_SET]`
- ✅ `ACCESS_COARSE_LOCATION: granted=true, flags=[USER_SET]`

**Conclusion:** All required location permissions are granted.

---

### Step 2.3: App Ops Verification
```bash
$ adb -s 30870044490006E shell appops get org.torproject.android.debug android:fine_location
```

**Results:**
```
Uid mode: FINE_LOCATION: foreground
FINE_LOCATION: allow; time=+13m54s881ms ago; rejectTime=+884ms ago
```

**Findings:**
- ✅ Fine location is **allowed** in foreground mode
- ⚠️ Mode is "foreground" only (not "foreground and background")
- ⚠️ Last successful access: 13m54s ago
- ⚠️ Last rejection: 884ms ago (recent rejection event)

**Note:** The recent rejection might indicate a permission check failure or request during background state.

---

## ROOT CAUSE HYPOTHESIS

Based on evidence from Phases 1 and 2, the most likely root cause is:

### **PRIMARY HYPOTHESIS: App requests GPS with wrong priority level**

**Evidence:**
1. System GPS provider is functional (Google Maps gets GPS location successfully)
2. App permissions are granted (ACCESS_FINE_LOCATION = true)
3. App successfully registers GPS requests with system
4. **BUT:** App requests location with `BALANCED` mode instead of `HIGH_ACCURACY` mode

**Impact:**
- BALANCED mode may use WiFi/cell tower triangulation instead of GPS satellites
- This could explain why location fails even outdoors with clear sky
- Google Maps uses HIGH_ACCURACY and gets GPS fix successfully

**Expected behavior:**
- App should request location with `HIGH_ACCURACY` priority to force GPS satellite usage
- Request should specify `GPS_PROVIDER` explicitly

---

## NEXT STEPS - PHASE 3: LIVE GPS TESTING

Logcat has been cleared. Ready for live monitoring.

### Instructions for User:

**Step 1: Start Background Log Monitoring**
Open a terminal and run:
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$PATH:$ANDROID_HOME/platform-tools"
adb -s 30870044490006E logcat | grep -E "LOCATION|LocationManager|EnhancedMeshFragment" | tee gps_test_phone1.log
```
Keep this terminal open and monitoring.

**Step 2: Trigger GPS Request in App**
1. Open Orbot app on Phone 1 (device 30870044490006E)
2. Navigate to Mesh tab
3. Tap "Send Broadcast" button
4. Check "Include GPS location" checkbox
5. Wait 60 seconds (or until timeout message appears)
6. Observe log output in terminal

**Step 3: Report Observations**
After 60 seconds, press Ctrl+C in the log terminal and report:
- Did you see "[LOCATION] Starting async GPS request" message?
- Did you see any permission errors?
- Did you see "GPS request registered successfully"?
- Did you see location coordinates appear in the dialog?
- What was the final message (success, timeout, or error)?

---

## CODE INVESTIGATION NEEDED

Based on findings, the following code sections should be examined:

### File: `EnhancedMeshFragment.kt`

**Location 1: Line ~1609-1612** - `startAsyncLocationRequest()` method
```kotlin
locationManager.requestSingleUpdate(
    android.location.LocationManager.GPS_PROVIDER,  // ← Verify this is GPS_PROVIDER
    listener,
    android.os.Looper.getMainLooper()
)
```

**Expected:** Should request from GPS_PROVIDER explicitly  
**Verify:** Check if code accidentally uses NETWORK_PROVIDER or FUSED_PROVIDER

---

**Location 2: Line ~1335-1365** - Checkbox listener permission check
```kotlin
if (ContextCompat.checkSelfPermission(requireContext(), 
    android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
    // Permission not granted path
}
```

**Expected:** Should pass permission check (we confirmed permissions are granted)  
**Verify:** Check if code path correctly reaches `startAsyncLocationRequest()` call

---

**Location 3: Location Request Parameters**
The GPS provider logs show:
```
Request[@0 BALANCED, duration=+30s0ms, maxUpdates=1, ...]
```

**Problem:** Request uses **BALANCED** priority instead of **HIGH_ACCURACY**

**Expected code (HIGH_ACCURACY):**
```kotlin
val locationRequest = LocationRequest.create().apply {
    priority = LocationRequest.PRIORITY_HIGH_ACCURACY  // Force GPS
    interval = 0
    fastestInterval = 0
    numUpdates = 1
}
```

**Current code might be using:**
```kotlin
priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY  // Uses WiFi/Cell
```

**ACTION REQUIRED:**  
Search codebase for `LocationRequest` or `Criteria` objects that set priority/accuracy.  
The BALANCED mode is likely being set somewhere in the location request configuration.

---

## COMPARATIVE ANALYSIS: Phone 1 vs Phone 2

**Phone 1 (30870044490006E):**
- System: Location enabled (mode=3) ✅
- Permissions: Granted ✅
- GPS Provider: Functional ✅
- App Request Mode: **BALANCED** ⚠️
- GPS Acquisition: **FAILS** ❌

**Phone 2 (LML211BL3f1c96e3):**
- Status: UNKNOWN (requires testing)
- Expected: Same permissions, same code
- GPS Acquisition: Need to verify if it works

**If Phone 2 also fails:** Code issue (wrong priority mode)  
**If Phone 2 succeeds:** Device-specific issue (but unlikely given Maps works on Phone 1)

---

## IMMEDIATE ACTION ITEMS

1. ✅ **COMPLETED:** Verify system GPS enabled
2. ✅ **COMPLETED:** Verify app permissions granted
3. ⏳ **IN PROGRESS:** Capture live GPS request logs
4. 🔍 **PENDING:** Search code for `LocationRequest` priority settings
5. 🔍 **PENDING:** Search code for `Criteria` accuracy settings
6. 🔧 **PENDING:** Change request mode from BALANCED to HIGH_ACCURACY
7. 🧪 **PENDING:** Test GPS acquisition after code fix

---

## REFERENCES

- Phone 1 Location Dump: `phone1_location_dump.txt`
- GPS Request History: See dumpsys location output above
- Android LocationManager API: https://developer.android.com/reference/android/location/LocationManager
- Location Request Priorities: https://developer.android.com/reference/com/google/android/gms/location/LocationRequest

---

**Investigation Status:** Phase 3 ready to begin (awaiting user test)  
**Confidence Level:** HIGH - Root cause likely identified (BALANCED vs HIGH_ACCURACY mode)  
**Next Update:** After live GPS test logs are captured
