# Mesh Network Issues - Root Cause Analysis and Proposed Solutions

**Date:** February 10, 2026  
**Logs Analyzed:** phone_test.log (Phone 1 - broadcaster), phone_test2.log (Phone 2 - receiver)  
**Issues:** 3 critical problems affecting mesh network functionality

---

## Executive Summary

Analysis of phone_test.log (Phone 1 - broadcaster, IP 169.254.60.182) and phone_test2.log (Phone 2 - receiver, IP 169.254.21.63) reveals three distinct root causes affecting the mesh network application:

**Issue 1 (Role Update Not Triggering):** EmergentRoleManager's `startWifiStateMonitoring()` monitors station connection but Phone 2 never detected the connection state transition, preventing automatic role recalculation.

**Issue 2 (Notifications Not Working):** The broadcast transfer was incomplete (only 332/5335 chunks received) before the log ended, so no notification was ever generated. The notification system itself appears functional.

**Issue 3 (File Not Saved):** The broadcast never completed, so the file was never reassembled or saved to SharedWithMe folder. Drop folder configuration may also be missing.

---

## Detailed Log Correlation

### Event Timeline (Using Event Markers, NOT Timestamps)

**Phone 1 Events:**
1. **Mesh Start:** `02-10 17:51:02` - App starts, mesh initialization begins
2. **Hotspot Created:** `AndroidShare_6871` with passphrase `3mtjvhd6bm3ey7w`
3. **Role Assignment:** Phone 1 roles transition from `[MESH_PARTICIPANT]` to `[MESH_PARTICIPANT, STORAGE_NODE, COMPUTE_NODE, MESH_ROUTER]`
4. **Status CONNECTING:** Mesh status shows CONNECTING
5. **Originating Messages Sent:** Phone 1 begins broadcasting originator messages announcing its presence
6. **Broadcast Initiated:** User sends broadcast with file `IMG_20220412_112530.jpg` (5335 chunks total)

**Phone 2 Events:**
1. **Mesh Start:** `02-10 22:51:01` - App starts, mesh initialization begins
2. **WiFi Join:** `02-10 22:51:40` - Successfully joined `AndroidShare_6871` network (`CTRL-EVENT-CONNECTED`)
3. **Neighbor Discovery:** `02-10 22:51:41` - Phone 2 discovers Phone 1 (169.254.60.182) as direct neighbor: `🤝 DIRECT NEIGHBOR detected: 169.254.60.182 (isNew=true)`
4. **Role Observer:** `02-10 22:51:29` - Only ONE role change event logged: `[ROLE_OBSERVER] Roles changed: [MESH_PARTICIPANT]`
5. **Broadcast Reception Starts:** `02-10 22:52:11` - Phone 2 starts receiving broadcast chunks: `New incoming broadcast: id=bf129751-a5c3-4370-9863-97024129f4d3, file=IMG_20220412_112530.jpg, totalChunks=5335`
6. **Incomplete Transfer:** `02-10 22:52:18` (end of log) - Only 332/5335 chunks received before log capture ended
7. **No File Completion:** No "all chunks received", "reassembling", "SharedWithMe", or "BroadcastReceivedDto" events in log

### Critical Findings

**Finding 1: Role Update Never Triggered on Phone 2**
- Phone 2 successfully connected to Phone 1's hotspot (confirmed by `CTRL-EVENT-CONNECTED`)
- Phone 2 discovered Phone 1 as a direct neighbor (confirmed by originator message reception)
- However, `updateRoles()` was **NEVER called** after connection
- Only one role change event in entire log: initial `[MESH_PARTICIPANT]` role set during mesh start
- No `[UPDATE_ROLES] ===== updateRoles() called` log entries after connection

**Finding 2: Broadcast Transfer Incomplete**
- Phone 2 IS receiving broadcast chunks correctly
- Transfer rate: ~47 chunks/second (332 chunks in ~7 seconds)
- At this rate, full transfer would require ~113 seconds
- Log capture ended prematurely at 332/5335 chunks (6.2% complete)
- No evidence of transfer completion, file reassembly, or notification generation

**Finding 3: No File Save Events**
- No "Drop folder" logs in Phone 2 log
- No "SharedWithMe folder" creation logs
- No "wrote broadcast file" success logs
- File was never reassembled because transfer never completed

---

## Validation by Falsification

### Issue 1: Role Update Not Triggering

**Hypothesis 1:** EmergentRoleManager's WiFi state monitoring is not working
- **Evidence:** EmergentRoleManager.kt:170-210 shows two monitors:
  - Hotspot status monitor (triggers on STARTED)
  - Station connection monitor (triggers when `wifiStationState.status == WifiStationState.Status.AVAILABLE`)
- **Test:** Check if station state transitioned to AVAILABLE
- **Result:** Phone 2 log shows successful WiFi connection but no evidence of `WifiStationState.Status.AVAILABLE` transition being detected
- **Conclusion:** **CONFIRMED** - Station connection state monitor likely not triggering or state not transitioning correctly

**Hypothesis 2:** The delay(2000) in station monitor prevents race conditions
- **Evidence:** Code includes `delay(2000) // Allow neighbors to be discovered` before calling `updateRoles()`
- **Test:** Check if neighbors were discovered before any hypothetical updateRoles call
- **Result:** Neighbors WERE discovered (Phone 1 detected at `t+39.39s`), but no subsequent updateRoles occurred
- **Conclusion:** **FALSIFIED** - The delay is not the issue; updateRoles is simply never being called

**Hypothesis 3:** Connection state changes are not being observed by EmergentRoleManager
- **Evidence:** Phone 2 log shows `CTRL-EVENT-CONNECTED` from wpa_supplicant at 22:51:40
- **Test:** Check if MeshrabiyaWifiManager state flow emits station status change
- **Result:** No logs showing WifiStationState changes in Phone 2 log
- **Conclusion:** **CONFIRMED** - WiFi state flow may not be emitting `Status.AVAILABLE` when station connects

**Hypothesis 4:** EmergentRoleManager's `startWifiStateMonitoring()` was never called
- **Evidence:** No explicit log showing startWifiStateMonitoring was invoked
- **Test:** Check if EmergentRoleManager was initialized properly
- **Result:** Phone 1 log shows role updates working correctly, suggesting the monitor IS active there
- **Conclusion:** **UNCERTAIN** - Needs code verification of when startWifiStateMonitoring is called

### Issue 2: Notifications Not Working

**Hypothesis 1:** Notification system is broken
- **Evidence:** Code path in EnhancedMeshFragment.kt:315-350 shows proper notification handling
- **Test:** Check if BroadcastReceivedDto was ever created and listener invoked
- **Result:** No "BroadcastReceivedDto" logs in Phone 2 log
- **Conclusion:** **FALSIFIED** - Notification system never ran because prerequisite (completed broadcast) never occurred

**Hypothesis 2:** Broadcast never completed
- **Evidence:** BroadcastMessageHandler.kt:360-410 shows notification only created after `state.isComplete()`
- **Test:** Check for "all chunks received, reassembling" log
- **Result:** No such log exists in Phone 2 log; only 332/5335 chunks received
- **Conclusion:** **CONFIRMED** - Broadcast incomplete, so notification never generated

**Hypothesis 3:** updateNotificationBadge() is broken
- **Evidence:** OrbotActivity.kt:513-525 shows simple TextView update logic
- **Test:** Method was never called because broadcast listener was never invoked
- **Conclusion:** **UNCERTAIN** - Cannot test until broadcast completion issue is resolved

### Issue 3: File Not Received/Saved

**Hypothesis 1:** Drop folder not configured on Phone 2
- **Evidence:** BroadcastMessageHandler.kt:617-625 throws `IllegalStateException` if `getDropFolderCallback()` returns null
- **Test:** Check Phone 2 log for drop folder initialization or error
- **Result:** No logs mentioning drop folder in Phone 2 log
- **Conclusion:** **HIGHLY LIKELY** - Drop folder probably not set, but irrelevant because broadcast never completed

**Hypothesis 2:** File saving logic is broken
- **Evidence:** BroadcastMessageHandler.kt:627-655 shows robust file writing with mkdirs() and duplicate handling
- **Test:** Logic never executed because broadcast incomplete
- **Conclusion:** **CANNOT TEST** - Prerequisite (completed broadcast) not met

**Hypothesis 3:** Broadcast transfer is failing or stalling
- **Evidence:** Phone 2 received 332 chunks consistently at ~47 chunks/second
- **Test:** Check for errors or stalls in chunk reception
- **Result:** Reception appears normal in captured timeframe; log simply ended prematurely
- **Conclusion:** **FALSIFIED FOR STALLING**, **CANNOT TEST FOR COMPLETION** - Transfer was progressing normally but log ended before completion

---

## Root Cause Analysis

### Issue 1: Role Update Not Triggering on Connection

**Root Cause:** EmergentRoleManager's WiFi station state monitor is not detecting the connection state transition on Phone 2.

**Evidence Chain:**
1. Phone 2 successfully joins WiFi network (wpa_supplicant confirms `CTRL-EVENT-CONNECTED`)
2. Phone 2 discovers Phone 1 as neighbor via originator messages
3. No `WifiStationState.Status.AVAILABLE` state change is detected or logged
4. EmergentRoleManager's station monitor coroutine never triggers `updateRoles()`
5. Phone 2 remains at `[MESH_PARTICIPANT]` role indefinitely

**Code Location:** [EmergentRoleManager.kt:186-203](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt#L186-L203)

**Specific Mechanism:** The station connection monitor uses:
```kotlin
virtualNode.meshrabiyaWifiManager.state
    .map { it.wifiStationState.status == WifiStationState.Status.AVAILABLE }
    .distinctUntilChanged()
    .collect { isConnected ->
        if (isConnected) {
            Log.d(TAG, "[WIFI_STATE] Station connected, triggering role recalculation")
            delay(2000)
            updateRoles(userInitiated = false)
        }
    }
```

**Problem:** Either:
- `wifiStationState.status` is not transitioning to `AVAILABLE` when station connects, OR
- The state flow is not emitting the state change, OR
- The distinctUntilChanged() is filtering out the transition

### Issue 2: Notifications Not Working

**Root Cause:** Notifications are not broken - they simply never run because the broadcast transfer never completes.

**Evidence Chain:**
1. Broadcast listener is registered properly in EnhancedMeshFragment.kt:315
2. BroadcastMessageHandler only invokes listener after `state.isComplete()` check passes
3. Phone 2 received only 332/5335 chunks before log ended
4. No completion, therefore no `BroadcastReceivedDto` created, therefore no listener invoked, therefore no notification

**Code Location:** [BroadcastMessageHandler.kt:365-406](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt#L365-L406)

**Secondary Issue:** Even if broadcast had completed, drop folder may not be configured, which would trigger:
- `hasError = true`
- `errorMessage = "No storage folder set"`
- Notification would show error instead of success

### Issue 3: File Not Received/Saved

**Root Cause:** File was never saved because broadcast transfer never completed. Only 6.2% of file was received before log capture ended.

**Evidence Chain:**
1. Broadcast started at `t+70.17s` (Phone 2 time)
2. Transfer progressing at ~47 chunks/second
3. Log ended at `t+77.21s` with only 332/5335 chunks received
4. File reassembly (`state.reassemble()`) never executed
5. SharedWithMe folder never created
6. File never written

**Code Location:** [BroadcastMessageHandler.kt:367-392](Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt#L367-L392)

**Contributing Factor:** Log capture ended prematurely (~7 seconds into a ~113 second transfer). This is a test methodology issue, not a code bug.

**Secondary Issue:** Drop folder likely not configured on Phone 2, which would cause save failure even if broadcast completed.

---

## Proposed Solutions

### Solution 1: Fix Role Update Triggering (Issue 1)

**Option A: Debug and Fix WiFi Station State Flow (Preferred)**

**Implementation:**
1. Add extensive logging to MeshrabiyaWifiManager's WiFi station state tracking
2. Log every state transition with timestamp and old/new status values
3. Verify that `WifiStationState.Status.AVAILABLE` is being set when wpa_supplicant reports `CTRL-EVENT-CONNECTED`
4. Ensure state flow emits this change

**Files to Modify:**
- `Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/wifi/MeshrabiyaWifiManager.kt` (or equivalent WiFi manager)
- Add logs in EmergentRoleManager station monitor to confirm state changes are observed

**Code Changes:**
```kotlin
// In MeshrabiyaWifiManager or equivalent
fun updateWifiStationState(newStatus: WifiStationState.Status) {
    val oldStatus = _state.value.wifiStationState.status
    Log.i(TAG, "[WIFI_STATE] Station status transition: $oldStatus -> $newStatus")
    _state.value = _state.value.copy(
        wifiStationState = _state.value.wifiStationState.copy(status = newStatus)
    )
}

// In EmergentRoleManager.kt
virtualNode.meshrabiyaWifiManager.state
    .map { 
        val status = it.wifiStationState.status
        Log.d(TAG, "[WIFI_STATE] State flow emit: station status = $status")
        status == WifiStationState.Status.AVAILABLE
    }
    .distinctUntilChanged()
    .collect { isConnected ->
        Log.d(TAG, "[WIFI_STATE] Station connection changed: isConnected=$isConnected")
        if (isConnected) {
            Log.d(TAG, "[WIFI_STATE] Station connected, triggering role recalculation")
            delay(2000)
            updateRoles(userInitiated = false)
        }
    }
```

**Option B: Add Fallback Peer Count Monitor**

**Implementation:**
Add an additional monitor that triggers role updates when peer count transitions from 0 to 1+

**Code Changes:**
```kotlin
// In EmergentRoleManager.kt
fun startPeerCountMonitoring() {
    CoroutineScope(Dispatchers.Default).launch {
        var previousPeerCount = 0
        while (true) {
            delay(3000) // Check every 3 seconds
            val currentPeerCount = virtualNode.neighbors().size
            if (previousPeerCount == 0 && currentPeerCount > 0) {
                Log.d(TAG, "[PEER_COUNT] Peers discovered ($currentPeerCount), triggering role recalculation")
                delay(2000) // Allow topology to stabilize
                updateRoles(userInitiated = false)
            }
            previousPeerCount = currentPeerCount
        }
    }
}
```

**Call in initialization:**
```kotlin
startWifiStateMonitoring()
startPeerCountMonitoring() // Add this
```

### Solution 2: Ensure Drop Folder Configuration (Issue 2 & 3 Prevention)

**Implementation:**
1. Add mandatory drop folder selection on first mesh start
2. Persist drop folder URI using SharedPreferences
3. Show prominent warning if drop folder not set when receiving broadcasts

**Files to Modify:**
- [EnhancedMeshFragment.kt](app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt)

**Code Changes:**
```kotlin
// In onViewCreated or mesh start logic
private fun checkDropFolderConfiguration() {
    val dropFolder = meshrabiyaApi.getDropFolder()
    if (dropFolder == null) {
        AlertDialog.Builder(requireContext())
            .setTitle("Configure Storage Folder")
            .setMessage("A storage folder is required to receive files via mesh broadcasts. Please select a folder now.")
            .setPositiveButton("Select Folder") { _, _ ->
                folderPickerLauncher.launch(null)
            }
            .setCancelable(false)
            .show()
    }
}
```

### Solution 3: Add Broadcast Transfer Progress UI (Issue 2 & 3 Visibility)

**Implementation:**
Add progress indicator for incoming broadcasts so users can see transfer status

**Code Changes:**
```kotlin
// In EnhancedMeshFragment.kt
private val incomingBroadcastProgress = mutableMapOf<String, Int>()

// Update broadcast listener to handle progress
broadcastListener = { broadcast: BroadcastReceivedDto ->
    if (broadcast.hasError) {
        // Existing error handling
    } else {
        // Show success notification and update UI
        Toast.makeText(requireContext(), 
            "File received: ${broadcast.fileName}", 
            Toast.LENGTH_SHORT).show()
    }
    // Remove from progress tracking
    incomingBroadcastProgress.remove(broadcast.broadcastId)
}

// Add chunk progress tracking (requires API extension)
// Register chunk progress listener
meshrabiyaApi.registerBroadcastProgressListener { broadcastId, received, total ->
    incomingBroadcastProgress[broadcastId] = (received * 100 / total)
    updateBroadcastProgressUI()
}
```

### Solution 4: Add Comprehensive Logging for Diagnostics

**Implementation:**
Add detailed logging at every critical point in broadcast reception flow

**Code Changes:**
```kotlin
// In BroadcastMessageHandler.kt
private fun writeBroadcastFile(fileName: String, fileBytes: ByteArray): String {
    logger(Log.DEBUG, "$TAG [FILE_SAVE] Starting file save: $fileName (${fileBytes.size} bytes)")
    
    val dropFolder = getDropFolderCallback()
    if (dropFolder == null) {
        logger(Log.ERROR, "$TAG [FILE_SAVE] Drop folder is null - cannot save file")
        throw IllegalStateException("Drop folder not selected")
    }
    
    logger(Log.INFO, "$TAG [FILE_SAVE] Drop folder path: ${dropFolder.absolutePath}, exists=${dropFolder.exists()}, canWrite=${dropFolder.canWrite()}")
    
    // ... rest of existing code with more logs
}
```

---

## Verification Checklist

After implementing solutions, verify:

**For Issue 1 (Role Updates):**
- [ ] Phone 2 log shows `[WIFI_STATE] Station connected, triggering role recalculation` after WiFi join
- [ ] Phone 2 log shows `[UPDATE_ROLES] ===== updateRoles() called` within 2-3 seconds of neighbor discovery
- [ ] Phone 2 roles transition from `[MESH_PARTICIPANT]` to include additional roles (STORAGE_NODE, COMPUTE_NODE, etc.)
- [ ] EnhancedMeshFragment shows updated roles in UI: `Roles: MESH_PARTICIPANT, STORAGE_NODE, ...`

**For Issue 2 (Notifications):**
- [ ] Allow full broadcast transfer to complete (wait ~2 minutes for test file)
- [ ] Phone 2 log shows `Broadcast $id: all chunks received, reassembling`
- [ ] Phone 2 log shows `BroadcastReceivedDto` creation and listener invocation
- [ ] Phone 2 UI shows notification badge with count > 0
- [ ] Tapping notification icon shows received broadcast in list

**For Issue 3 (File Saving):**
- [ ] Phone 2 has drop folder configured before test
- [ ] Phone 2 log shows `SharedWithMe folder path: ...`
- [ ] Phone 2 log shows `mkdirs() result: true` or `SharedWithMe folder already exists`
- [ ] Phone 2 log shows `Wrote broadcast file: .../SharedWithMe/IMG_20220412_112530.jpg (...bytes)`
- [ ] File exists in file manager at expected path
- [ ] File size matches original file on Phone 1
- [ ] File content is not corrupted (can be opened/viewed)

**General Integration:**
- [ ] Repeat test with fresh app installs (clean state)
- [ ] Test with different file sizes (small, medium, large)
- [ ] Test with multiple consecutive broadcasts
- [ ] Verify no memory leaks or resource exhaustion
- [ ] Confirm battery impact is acceptable

---

## Additional Recommendations

1. **Extend Log Capture Duration:** For future testing, capture logs for at least 3 minutes after broadcast initiation to ensure completion

2. **Add Broadcast Transfer Timeout:** Implement automatic cleanup and user notification if transfer stalls for > 120 seconds

3. **Add Network Quality Metrics:** Log WiFi signal strength, packet loss rate, and throughput during broadcasts to identify network issues

4. **Implement Chunk Request Retransmission:** Add NACK mechanism for missing chunks (partially present in code at line 424) and verify it triggers correctly

5. **Add Health Check System:** Periodic verification that all critical components (WiFi state monitor, role manager, broadcast handler) are functioning

---

## Summary

**Primary Issue:** WiFi station state monitoring not triggering role updates on Phone 2  
**Priority:** HIGH - Affects core mesh functionality  
**Recommended First Step:** Implement Solution 1 Option B (peer count monitor) as immediate workaround, then debug WiFi state flow  

**Secondary Issues:** Test methodology (log capture too short) and configuration (drop folder not set)  
**Priority:** MEDIUM - Need longer test runs and mandatory drop folder selection  

**Test Improvements Needed:**
- Capture logs for full broadcast transfer duration (2-3 minutes minimum)
- Configure drop folder before testing broadcast reception
- Verify role updates occur automatically without user interaction
- Monitor notification system after confirmed broadcast completion
