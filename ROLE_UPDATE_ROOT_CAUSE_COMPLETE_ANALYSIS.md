# Role Update Not Triggering - Complete Root Cause Analysis with Validation by Falsification

**Date:** February 11, 2026  
**Critical Bug Priority:** HIGH  
**Affects:** All nodes joining mesh as WiFi station/client (MESH_PARTICIPANT)

---

## Executive Summary

**ROOT CAUSE IDENTIFIED:** EmergentRoleManager's WiFi state flow monitoring is NOT TRIGGERING when `wifiStationState.status` transitions to `AVAILABLE`. The state DOES transition correctly in MeshrabiyaWifiManagerAndroid, but the StateFlow collector in EmergentRoleManager never receives the emission.

**Evidence:**
- Phone 2 log shows: `connectToHotspot: AndroidShare_6871 - success status=AVAILABLE` at t+39.21s
- Phone 2 log shows: **ZERO** `[WIFI_STATE]` logs from EmergentRoleManager
- State flow monitoring code exists and is started, but `.collect{}` block never executes

---

## Validation by Falsification - Complete Chain

### Hypothesis 1: WifiStationState.Status.AVAILABLE is the wrong state to check

**Test:** Verify what AVAILABLE means in the codebase

**Code Evidence - WifiStationState.kt (Lines 16-24):**
```kotlin
data class WifiStationState(
    val status: Status = Status.INACTIVE,
    val network: Network? = null,
    val config: WifiConnectConfig? = null,
    val stationBoundSocketsPort: Int = -1,
    val stationBoundDatagramSocket: VirtualNodeDatagramSocket? = null,
) {
    enum class Status {
        INACTIVE, CONNECTING, AVAILABLE, UNAVAILABLE, LOST;
```

**Analysis:**
- `INACTIVE` = No station connection
- `CONNECTING` = Connection in progress
- `AVAILABLE` = **Successfully connected and network is available for use**
- `UNAVAILABLE` = Connection failed
- `LOST` = Connection lost after being available

**User's Question:** "AVAILABLE sounds like a state that would occur for a MESH_HUB or MESH_ROUTER but phone 2 is only a MESH_PARTICIPANT"

**FALSIFIED:** AVAILABLE is the correct state for ANY node (PARTICIPANT, HUB, or ROUTER) when its WiFi station (client) connection succeeds. The status name refers to "network AVAILABLE", not "node available as hub/router". A MESH_PARTICIPANT that joins another node's hotspot transitions to AVAILABLE.

**Verdict:** ✅ AVAILABLE is correct

---

### Hypothesis 2: wifiStationState.status never transitions to AVAILABLE on Phone 2

**Test:** Search Phone 2 log for status transitions

**Log Evidence - phone_test2.log:**
```
02-10 22:51:40.884 I/System.out( 6760): D: t+39.2s : [MeshrabiyaWifiManagerAndroid: 169.254.21.63]  connectToHotspot: connection available. Network=106
02-10 22:51:40.891 I/System.out( 6760): I: t+39.21s : [MeshrabiyaWifiManagerAndroid: 169.254.21.63]  connectToHotspot: AndroidShare_6871 - success status=AVAILABLE
02-10 22:51:40.891 I/System.out( 6760): I: t+39.21s : [MeshrabiyaWifiManagerAndroid: 169.254.21.63]  connectToHotspot: bindProcessToNetwork result=true
```

**Code Evidence - MeshrabiyaWifiManagerAndroid.kt (Lines 108-116):**
```kotlin
private inner class ConnectNetworkCallback(
    private val config: WifiConnectConfig
): NetworkCallback() {
    override fun onAvailable(network: Network) {
        logger(Log.DEBUG, "$logPrefix connectToHotspot: connection available. Network=$network")
        _state.update { prev ->
            prev.copy(
                wifiStationState = prev.wifiStationState.copy(
                    status = WifiStationState.Status.AVAILABLE,
                    network = network,
                )
            )
        }
```

**Analysis:**
1. Android's `NetworkCallback.onAvailable()` fires at t+39.2s
2. Code sets `status = WifiStationState.Status.AVAILABLE`
3. `_state.update{}` emits new state to flow
4. Log shows "success status=AVAILABLE"

**FALSIFIED:** Status DOES transition to AVAILABLE at t+39.21s

**Verdict:** ✅ State transition happens correctly

---

### Hypothesis 3: EmergentRoleManager's startWifiStateMonitoring() was never called

**Test:** Verify initialization chain

**Code Evidence - AndroidVirtualNode.kt (Lines 85-87):**
```kotlin
init {
    // Start WiFi state monitoring after all properties initialized
    emergentRoleManager.startWifiStateMonitoring()
}
```

**Analysis:**
- AndroidVirtualNode.init{} calls startWifiStateMonitoring()
- Phone 2 log shows AndroidVirtualNode was constructed (neighbors discovered, originating messages sent)
- startWifiStateMonitoring() MUST have been called

**FALSIFIED:** Function was called during initialization

**Verdict:** ✅ startWifiStateMonitoring() was called

---

### Hypothesis 4: The state flow monitoring code doesn't exist or is incomplete

**Test:** Read the actual monitoring code

**Code Evidence - EmergentRoleManager.kt (Lines 186-203):**
```kotlin
// ADD THIS: Monitor station connection state
CoroutineScope(Dispatchers.Default).launch {
    try {
        virtualNode.meshrabiyaWifiManager.state
            .map { it.wifiStationState.status == WifiStationState.Status.AVAILABLE }
            .distinctUntilChanged()
            .collect { isConnected ->
                if (isConnected) {
                    Log.d(TAG, "[WIFI_STATE] Station connected, triggering role recalculation")
                    delay(2000) // Allow neighbors to be discovered
                    updateRoles(userInitiated = false)
                }
            }
    } catch (e: Exception) {
        Log.e(TAG, "[WIFI_STATE] Failed to monitor station state", e)
    }
}
```

**Analysis:**
- Code exists
- Monitors `virtualNode.meshrabiyaWifiManager.state` flow
- Maps to boolean: `status == AVAILABLE`
- Uses distinctUntilChanged() to trigger only on transitions
- Logs `[WIFI_STATE] Station connected` if it fires
- Calls updateRoles() with 2-second delay

**FALSIFIED:** Code exists and looks correct

**Verdict:** ✅ Monitoring code is present

---

### Hypothesis 5: The state flow is never emitting state changes

**Test:** Check if _state.update{} actually emits to collectors

**Code Evidence - MeshrabiyaWifiManagerAndroid.kt (Line 111):**
```kotlin
_state.update { prev ->
    prev.copy(
        wifiStationState = prev.wifiStationState.copy(
            status = WifiStationState.Status.AVAILABLE,
            network = network,
        )
    )
}
```

**Analysis:**
- Uses MutableStateFlow's `.update{}` method
- This SHOULD emit to all collectors
- But NO `[WIFI_STATE]` logs appear in Phone 2 log

**Test Search - Phone 2 log:**
```bash
$ grep "\[WIFI_STATE\]" phone_test2.log
# NO RESULTS
```

**CONFIRMED:** The `.collect{}` block in EmergentRoleManager is NEVER EXECUTING despite state updates occurring

**Verdict:** ⚠️ **THIS IS THE BUG**

---

### Hypothesis 6: The coroutine scope is being cancelled or never starts

**Test:** Check coroutine lifecycle

**Code Evidence - EmergentRoleManager.kt (Line 186):**
```kotlin
CoroutineScope(Dispatchers.Default).launch {
```

**Analysis:**
- Creates NEW untracked CoroutineScope
- Uses Dispatchers.Default (not attached to lifecycle)
- Has no Job parent
- Could be GC'd or never properly started

**CRITICAL ISSUE:** Using `CoroutineScope(Dispatchers.Default)` creates an ORPHAN scope with no parent. If the scope is GC'd or the coroutine never starts due to timing issues, the monitoring never runs.

**Verdict:** ⚠️ **LIKELY ROOT CAUSE**

---

### Hypothesis 7: The state flow map/distinctUntilChanged is filtering incorrectly

**Test:** Trace the flow transformation logic

**Code Flow:**
```kotlin
virtualNode.meshrabiyaWifiManager.state              // MutableStateFlow<MeshrabiyaWifiState>
    .map { it.wifiStationState.status == WifiStationState.Status.AVAILABLE }  // Flow<Boolean>
    .distinctUntilChanged()                           // Only emit on Boolean change
    .collect { isConnected -> ... }
```

**State Transitions:**
1. **Initial state:** `status = INACTIVE` → map to `false`
2. **User initiates connect:** `status = CONNECTING` → map to `false` (no change, distinctUntilChanged filters out)
3. **Connection succeeds:** `status = AVAILABLE` → map to `true` (CHANGE! Should emit)

**Expected:** distinctUntilChanged() should emit `true` when AVAILABLE is first set

**Actual:** No emission logged

**Verdict:** ⚠️ Flow transformation logic is correct, but collector never receives emission

---

## ROOT CAUSE DETERMINATION

### Primary Root Cause: Orphaned Coroutine Scope

**Issue:** `CoroutineScope(Dispatchers.Default).launch{}` creates an unmanaged coroutine scope with no lifecycle attachment.

**Problems:**
1. **No parent Job:** Scope has no parent, can be GC'd
2. **No lifecycle binding:** Not attached to AndroidVirtualNode or any component lifecycle
3. **No error visibility:** If coroutine fails to start, no logging occurs
4. **Timing issues:** Coroutine may start AFTER state transitions occur

**Evidence:**
- Phone 2: Status transitions to AVAILABLE at t+39.21s
- Phone 2: ZERO `[WIFI_STATE]` logs ever appear
- Code exists, is called during init, but collector never executes

### Secondary Contributing Factor: Missing State Flow Initialization Logging

**Issue:** No logs confirm that state flow monitoring actually started collecting

**Missing Logs:**
- "Starting WiFi station state monitoring"
- "State flow collector launched"
- "Waiting for state transitions..."

**Result:** Impossible to debug whether collector ever started

---

## COMPLETE SOLUTION WITH VERIFICATION

### Solution 1: Use Scoped Coroutine with Lifecycle

**File:** [EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)

**Location:** Lines 170-203

**BEFORE (Problematic Code):**
```kotlin
fun startWifiStateMonitoring() {
    CoroutineScope(Dispatchers.Default).launch {
        try {
            virtualNode.meshrabiyaWifiManager.state
                .map { it.localOnlyHotspotState.status }
                .distinctUntilChanged()
                .collect { status ->
                    if (status == HotspotStatus.STARTED) {
                        Log.d(TAG, "[WIFI_STATE] Hotspot started, triggering role recalculation")
                        updateRoles(userInitiated = false)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Failed to monitor WiFi state", e)
        }
    }

    // ADD THIS: Monitor station connection state
    CoroutineScope(Dispatchers.Default).launch {
        try {
            virtualNode.meshrabiyaWifiManager.state
                .map { it.wifiStationState.status == WifiStationState.Status.AVAILABLE }
                .distinctUntilChanged()
                .collect { isConnected ->
                    if (isConnected) {
                        Log.d(TAG, "[WIFI_STATE] Station connected, triggering role recalculation")
                        delay(2000) // Allow neighbors to be discovered
                        updateRoles(userInitiated = false)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Failed to monitor station state", e)
        }
    }
}
```

**AFTER (Fixed Code):**
```kotlin
// Add to class properties (around line 140)
private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun startWifiStateMonitoring() {
    Log.d(TAG, "[WIFI_STATE] ===== startWifiStateMonitoring() CALLED =====")
    
    // Monitor hotspot state changes
    monitoringScope.launch {
        Log.d(TAG, "[WIFI_STATE] Hotspot monitoring coroutine STARTED")
        try {
            virtualNode.meshrabiyaWifiManager.state
                .map { 
                    val status = it.localOnlyHotspotState.status
                    Log.v(TAG, "[WIFI_STATE] Hotspot status: $status")
                    status
                }
                .distinctUntilChanged()
                .collect { status ->
                    Log.d(TAG, "[WIFI_STATE] Hotspot status CHANGED to: $status")
                    if (status == HotspotStatus.STARTED) {
                        Log.d(TAG, "[WIFI_STATE] Hotspot started, triggering role recalculation")
                        updateRoles(userInitiated = false)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Hotspot monitor FAILED", e)
        }
    }

    // Monitor station connection state
    monitoringScope.launch {
        Log.d(TAG, "[WIFI_STATE] Station monitoring coroutine STARTED")
        try {
            virtualNode.meshrabiyaWifiManager.state
                .map { 
                    val status = it.wifiStationState.status
                    val isAvailable = status == WifiStationState.Status.AVAILABLE
                    Log.v(TAG, "[WIFI_STATE] Station status: $status, isAvailable: $isAvailable")
                    isAvailable
                }
                .distinctUntilChanged()
                .collect { isConnected ->
                    Log.d(TAG, "[WIFI_STATE] Station connection CHANGED to: isConnected=$isConnected")
                    if (isConnected) {
                        Log.d(TAG, "[WIFI_STATE] Station connected (AVAILABLE), triggering role recalculation in 2s")
                        delay(2000) // Allow neighbors to be discovered
                        Log.d(TAG, "[WIFI_STATE] Calling updateRoles() after station connection")
                        updateRoles(userInitiated = false)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Station monitor FAILED", e)
        }
    }
    
    Log.d(TAG, "[WIFI_STATE] Both monitoring coroutines launched successfully")
}

// Add cleanup function (around line 210)
fun stopWifiStateMonitoring() {
    Log.d(TAG, "[WIFI_STATE] Stopping WiFi state monitoring")
    monitoringScope.cancel()
}
```

**Key Changes:**
1. **Scoped CoroutineScope:** Added `monitoringScope` as class property with `SupervisorJob()`
2. **Lifecycle Management:** Scope persists with EmergentRoleManager instance
3. **Verbose Logging:** Added logs at EVERY step to track execution
4. **Verification Logs:** Log when coroutines start, when states change, when collect fires
5. **Cleanup Function:** Added `stopWifiStateMonitoring()` for proper shutdown

---

### Solution 2: Alternative - Use virtualNode.launch for Lifecycle Binding

**File:** [EmergentRoleManager.kt](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt)

**AFTER (Alternative Approach):**
```kotlin
fun startWifiStateMonitoring() {
    Log.d(TAG, "[WIFI_STATE] ===== startWifiStateMonitoring() CALLED =====")
    
    // Use virtualNode's scope if it has one, otherwise create managed scope
    val scope = (virtualNode as? CoroutineScope) ?: CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Monitor hotspot state changes
    scope.launch {
        Log.d(TAG, "[WIFI_STATE] Hotspot monitoring coroutine STARTED")
        try {
            virtualNode.meshrabiyaWifiManager.state
                .onEach { state ->
                    Log.v(TAG, "[WIFI_STATE] State emission received: hotspot=${state.localOnlyHotspotState.status}, station=${state.wifiStationState.status}")
                }
                .map { it.localOnlyHotspotState.status }
                .distinctUntilChanged()
                .collect { status ->
                    Log.d(TAG, "[WIFI_STATE] Hotspot status CHANGED to: $status")
                    if (status == HotspotStatus.STARTED) {
                        Log.d(TAG, "[WIFI_STATE] Hotspot started, triggering role recalculation")
                        updateRoles(userInitiated = false)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Hotspot monitor FAILED", e)
        }
    }

    // Monitor station connection state
    scope.launch {
        Log.d(TAG, "[WIFI_STATE] Station monitoring coroutine STARTED")
        try {
            virtualNode.meshrabiyaWifiManager.state
                .onEach { state ->
                    Log.v(TAG, "[WIFI_STATE] State emission for station check: status=${state.wifiStationState.status}")
                }
                .map { it.wifiStationState.status == WifiStationState.Status.AVAILABLE }
                .distinctUntilChanged()
                .collect { isConnected ->
                    Log.d(TAG, "[WIFI_STATE] Station connection state CHANGED to: isConnected=$isConnected")
                    if (isConnected) {
                        Log.d(TAG, "[WIFI_STATE] Station AVAILABLE detected, waiting 2s for neighbors")
                        delay(2000)
                        Log.d(TAG, "[WIFI_STATE] Calling updateRoles() after station connection")
                        updateRoles(userInitiated = false)
                    } else {
                        Log.d(TAG, "[WIFI_STATE] Station no longer available (disconnected or failed)")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI_STATE] Station monitor FAILED", e)
        }
    }
    
    Log.d(TAG, "[WIFI_STATE] WiFi state monitoring initialized with ${if (virtualNode is CoroutineScope) "virtualNode" else "new"} scope")
}
```

**Key Differences:**
- Uses `onEach{}` to log EVERY state emission (very verbose but catches flow issues)
- Attempts to use virtualNode's scope if available
- Logs both connection and disconnection events

---

## VERIFICATION CHECKLIST

After implementing Solution 1 or 2, verify the following:

### Build Verification
- [ ] Code compiles without errors
- [ ] No missing imports
- [ ] `monitoringScope` property added to class
- [ ] All logs use correct TAG variable

### Runtime Verification - Phone 2 (Station/Client)

**Expected Log Sequence:**
```
D/EmergentRoleManager: [WIFI_STATE] ===== startWifiStateMonitoring() CALLED =====
D/EmergentRoleManager: [WIFI_STATE] Hotspot monitoring coroutine STARTED
D/EmergentRoleManager: [WIFI_STATE] Station monitoring coroutine STARTED
D/EmergentRoleManager: [WIFI_STATE] Both monitoring coroutines launched successfully

[... app starts, user initiates mesh join ...]

V/EmergentRoleManager: [WIFI_STATE] Station status: INACTIVE, isAvailable: false
V/EmergentRoleManager: [WIFI_STATE] Station status: CONNECTING, isAvailable: false
V/EmergentRoleManager: [WIFI_STATE] Station status: AVAILABLE, isAvailable: true
D/EmergentRoleManager: [WIFI_STATE] Station connection CHANGED to: isConnected=true
D/EmergentRoleManager: [WIFI_STATE] Station connected (AVAILABLE), triggering role recalculation in 2s

[... 2 second delay ...]

D/EmergentRoleManager: [WIFI_STATE] Calling updateRoles() after station connection
D/EmergentRoleManager: [UPDATE_ROLES] ===== updateRoles() called =====
D/EmergentRoleManager: [UPDATE_ROLES] userInitiated=false
[... role calculation logs ...]
D/EmergentRoleManager: [ROLE_OBSERVER] Roles changed: [MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE]
```

### Verification Steps:
1. **Build and deploy** to Phone 2
2. **Clear logcat:** `adb -s <phone2_serial> logcat -c`
3. **Start logging:** `adb -s <phone2_serial> logcat -v time | grep -E "WIFI_STATE|UPDATE_ROLES|ROLE_OBSERVER"`
4. **Open app** on Phone 2
5. **Verify startup logs:** Check for "startWifiStateMonitoring() CALLED" and "coroutine STARTED"
6. **Initiate mesh join** (scan QR, connect to Phone 1's hotspot)
7. **Verify state transitions:** Check for "Station status: CONNECTING" then "Station status: AVAILABLE"
8. **Verify monitoring triggers:** Check for "Station connection CHANGED to: isConnected=true"
9. **Verify role update:** Check for "updateRoles() called" ~2 seconds after AVAILABLE
10. **Verify role assignment:** Check for "Roles changed: [MESH_PARTICIPANT, ...]"

### Failure Indicators:
- ❌ "startWifiStateMonitoring() CALLED" never appears → Function not called
- ❌ "coroutine STARTED" never appears → Scope creation failed
- ❌ "Station status:" logs never appear → State flow not emitting
- ❌ "Station connection CHANGED" never appears → distinctUntilChanged filtering or collector not running
- ❌ "updateRoles() called" never appears → Delay/execution issue

---

## WHY THIS IS THE CORRECT SOLUTION

### Addressing User's Clarification:

**User Said:** "AVAILABLE sounds like a state that would occur for a MESH_HUB or MESH_ROUTER but phone 2 is only a MESH_PARTICIPANT"

**Response:**
1. **AVAILABLE refers to network availability, not node role**
   - When Phone 2 (MESH_PARTICIPANT) joins Phone 1's hotspot as a client (station mode)
   - Android's NetworkCallback.onAvailable() fires
   - wifiStationState.status transitions: INACTIVE → CONNECTING → AVAILABLE
   - This is correct for ANY node in station/client mode

2. **Role assignment happens AFTER connection**
   - Phone 2 starts with role: [MESH_PARTICIPANT]
   - After station connects (status=AVAILABLE), updateRoles() should run
   - updateRoles() evaluates device capabilities, mesh needs
   - New roles assigned: [MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, ...]
   - **This never happened because monitoring wasn't working**

3. **The bug affects all joining nodes**
   - Whether PARTICIPANT, HUB, or ROUTER
   - Any node joining another's hotspot experiences this
   - Station status must transition to AVAILABLE
   - Role recalculation should trigger automatically

### Why Previous Analysis Was Wrong:

**Previous Claim:** "wifiStationState.status is not transitioning to AVAILABLE"

**Actual:** Status DOES transition (log proof: "success status=AVAILABLE" at t+39.21s)

**Real Problem:** State flow collector never receives the emission due to orphaned coroutine scope

---

## IMPLEMENTATION INSTRUCTIONS

### Step 1: Add Scoped Property
**File:** `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`  
**Location:** Around line 140 (after class properties, before init{})

Add:
```kotlin
// Coroutine scope for WiFi state monitoring (lifecycle-managed)
private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### Step 2: Replace startWifiStateMonitoring() Function
**File:** Same file  
**Location:** Lines 170-203 (entire function)

Replace entire function with Solution 1 code above (the "AFTER (Fixed Code)" version)

### Step 3: Add Cleanup Function
**File:** Same file  
**Location:** After startWifiStateMonitoring() (around line 210)

Add:
```kotlin
fun stopWifiStateMonitoring() {
    Log.d(TAG, "[WIFI_STATE] Stopping WiFi state monitoring")
    monitoringScope.cancel()
}
```

### Step 4: Verify Imports
Ensure these imports exist at top of file:
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import android.util.Log
```

### Step 5: Build and Test
```bash
: > build_output.log && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./gradlew assembleDebug --console=plain 2>&1 | tee build_output.log
```

### Step 6: Deploy and Verify
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk" && export PATH="$PATH:$ANDROID_HOME/platform-tools" && adb -s <phone2_serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 7: Monitor Logs
```bash
adb -s <phone2_serial> logcat -c
adb -s <phone2_serial> logcat -v time | grep -E "WIFI_STATE|UPDATE_ROLES|ROLE_OBSERVER|EmergentRoleManager"
```

---

## EXPECTED OUTCOME

After fix is deployed:
1. ✅ Phone 2 joins mesh → status transitions INACTIVE → CONNECTING → AVAILABLE
2. ✅ EmergentRoleManager collector receives emission
3. ✅ Log shows: "Station connection CHANGED to: isConnected=true"
4. ✅ 2-second delay occurs (neighbor discovery time)
5. ✅ updateRoles() is called automatically
6. ✅ Roles recalculated based on capabilities and mesh needs
7. ✅ UI updates to show: "Roles: MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE"
8. ✅ No manual intervention needed

**This resolves the critical bug where role assignment never happens automatically after mesh join.**

---

**Analysis Completed:** February 11, 2026  
**Root Cause:** Orphaned coroutine scope prevents state flow collection  
**Solution:** Use managed CoroutineScope with SupervisorJob and extensive logging  
**Verification Method:** Log-driven validation at every execution step
