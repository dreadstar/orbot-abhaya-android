# Mesh Broadcast Analysis - February 9, 2026

## Executive Summary

Phone 1 broadcast a file (IMG_20220412_112558.jpg, 6555 chunks) to Phone 2. **Three critical failures occurred:**

1. **Incomplete Transfer**: Only 6400/6555 chunks received (97.6%) - transfer stopped prematurely
2. **No Notification**: Broadcast listener never triggered, no notification shown
3. **No File Saved**: SharedWithMe folder not created, file not written to disk

---

## Timeline Analysis

### Phone 2 (Receiver) - Key Events

| Time | Event | Evidence |
|------|-------|----------|
| 12:35:06.605 | Join mesh initiated | Line 1792: `Calling joinMesh() with QR data` |
| 12:35:10.618 | **Handler initialized** | Line 2539: `Broadcast handler initialized and wired to VirtualNode (joinMesh)` ✅ |
| 12:35:10.622 | Join mesh succeeded | Line 2541: `joinMesh() succeeded` |
| 12:35:45.545 | **First chunk received** | Line 4893: `chunk=1/6555` |
| 12:38:21.501 | **Last chunk received** | Last log: `6400/6555 chunks received` |
| Never | File write attempted | ❌ No logs for "reassembling", "Drop folder", or "SharedWithMe" |
| Never | Notification triggered | ❌ No broadcast listener callback |

### Packet Reception Statistics

- **Total VirtualNode packets detected**: 6,555 (verified with `grep -c`)
- **Total BroadcastMessageHandler chunks received**: 6,400
- **Missing chunks**: 155 chunks (2.4% loss)
- **Duration**: ~2 minutes 36 seconds

---

## Issue 1: Incomplete Transfer (6400/6555 Chunks)

### Evidence

```
Phone 2 Log - Last Handler Messages:
02-09 12:38:21.457: BroadcastMessageHandler Broadcast 622f0d34-...: 6397/6555 chunks received
02-09 12:38:21.460: BroadcastMessageHandler Broadcast 622f0d34-...: 6398/6555 chunks received
02-09 12:38:21.476: BroadcastMessageHandler Broadcast 622f0d34-...: 6399/6555 chunks received
02-09 12:38:21.501: BroadcastMessageHandler Broadcast 622f0d34-...: 6400/6555 chunks received
[NO MORE LOGS AFTER THIS]
```

### Analysis Using Validation by Falsification

**HYPOTHESIS 1**: VirtualNode stopped receiving packets  
**FALSIFICATION**: VirtualNode received all 6,555 packets (verified with grep count)  
**RESULT**: ❌ Disproven - VirtualNode received ALL packets

**HYPOTHESIS 2**: BroadcastMessageHandler stopped processing packets  
**VERIFICATION**: Handler processed 6,400 chunks, then stopped logging  
**RESULT**: ✅ Confirmed - Handler stopped mid-transfer

**HYPOTHESIS 3**: Duplicate chunk indices filled HashMap prematurely  
**VERIFICATION**: Logs show duplicate chunk indices (e.g., chunk=6552 appears twice at lines 4928, 4929)  
**CODE EVIDENCE**:
```kotlin
// BroadcastState.kt:33
fun isComplete(): Boolean = receivedChunks.size == metadata.totalChunks
```
Map size = unique keys, so duplicates don't increase size.

**RESULT**: ✅ Confirmed - Duplicate chunks explain discrepancy

### Root Cause

**BroadcastMessageHandler received 155 duplicate packets** (6555 - 6400 = 155). The `ConcurrentHashMap` only stores unique chunk indices, so:
- 6,555 packets arrived at VirtualNode
- 6,400 unique chunks stored in map
- 155 were duplicates of existing chunks
- Transfer never completed because 155 unique chunks were never sent

**Location of Logic**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastState.kt:33`

```kotlin
fun isComplete(): Boolean = receivedChunks.size == metadata.totalChunks
```

### Why This Happened

**Phone 1 (Broadcaster) Issue**: Sender did not transmit all chunks or sent wrong chunk indices. Need to verify Phone 1 logs for:
1. Did sender complete all 6,555 chunks?
2. Were all chunk indices 0-6554 sent exactly once?
3. Were there retransmissions causing duplicates?

---

## Issue 2: Roles Not Updating Until Screen Wake

### Evidence

```
Phone 2 Log - Role Assignment:
02-09 12:34:59.650: [ROLE_OBSERVER] Roles changed: [MESH_PARTICIPANT]
02-09 12:34:59.654: [ROLE_OBSERVER] UI updated - meshStarted: false, roles: MESH_PARTICIPANT
```

This occurred at **12:34:59**, which is **BEFORE** joining the mesh (12:35:06).

### Analysis

**HYPOTHESIS**: UI not updating after mesh connection  
**VERIFICATION**: Need to check role observer registration and update triggers  
**CODE LOCATION**: Search for role observer in `EnhancedMeshFragment.kt` and `EmergentRoleManager`

**SOLUTION NEEDED**:
1. Register role observer AFTER successful join
2. Add periodic role check (every 5-10 seconds) while connected
3. Trigger role update on mesh status change to CONNECTED

---

## Issue 3: No Notifications Shown

### Evidence

**Searched for notification-related logs**: Found ZERO lines related to broadcast notifications being stored or displayed.

**Expected Flow**:
1. BroadcastMessageHandler calls `isComplete()` → true
2. Handler reassembles file and writes to disk
3. Handler invokes all registered listeners with `BroadcastReceivedDto`
4. EnhancedMeshFragment's `broadcastListener` receives callback
5. Listener stores notification in `receivedBroadcasts` list
6. UI updates notification badge

### Analysis Using Validation by Falsification

**HYPOTHESIS 1**: Listener not registered  
**VERIFICATION**: Code shows registration at line 374:
```kotlin
meshrabiyaApi.registerBroadcastListener(broadcastListener)
```
**RESULT**: ❌ Disproven - Listener IS registered

**HYPOTHESIS 2**: Transfer never completed so listener never called  
**VERIFICATION**: Logs show `isComplete()` never returned true (no "all chunks received, reassembling" log)  
**RESULT**: ✅ Confirmed - Listener never triggered because transfer incomplete

**CODE EVIDENCE**:
```kotlin
// BroadcastMessageHandler.kt:250-252
if (state.isComplete()) {
    logger(Log.INFO, "$TAG Broadcast $broadcastId: all chunks received, reassembling")
    // ... then notify listeners
}
```

**Phone 2 logs**: ❌ No "all chunks received" log found

### Root Cause

**Notifications were never created because the broadcast never completed.** Only 6400/6555 chunks received, so `isComplete()` never returned true, listener never invoked, no notification stored.

**Cascading Failure**:
1. Phone 1 didn't send all chunks (or sent duplicates instead of missing chunks)
2. `isComplete()` always returned false
3. File never reassembled or written
4. Listener never notified
5. No notification badge or dropdown

---

## Issue 4: SharedWithMe Folder Not Created

### Evidence

**Searched logs for file write attempts**:
```bash
grep -n "Drop folder\|SharedWithMe\|writing file" phone_test2.log
# RESULT: 0 matches
```

**Expected logs** (from BroadcastMessageHandler.kt:263-277):
```
"Broadcast $broadcastId: attempting to write file"
"Drop folder path: /path/to/drop"
"SharedWithMe folder path: /path/to/SharedWithMe"
"mkdirs() result: true"
"Broadcast $broadcastId: complete, file written to $filePath"
```

**Actual logs**: ❌ NONE of these appeared

### Root Cause

**File write code never executed because `isComplete()` never returned true.** The file write logic is inside the `if (state.isComplete())` block (BroadcastMessageHandler.kt:250-290).

---

## Solutions Required

### 1. Fix Incomplete Transfer (CRITICAL)

**Problem**: Only 6400/6555 unique chunks received, 155 chunks missing

**Phone 1 Investigation Needed**:
- Verify sender broadcast all 6,555 chunks with correct indices
- Check for off-by-one errors in chunk loop
- Verify no chunks skipped (check indices 0-6554 all sent)

**Potential Code Fix** (if sender issue):
```kotlin
// In BroadcastMessageHandler.kt outgoing broadcast
for (chunkIndex in 0 until totalChunks) {  // Ensure inclusive of last chunk
    val chunk = file.getChunk(chunkIndex)
    broadcast(chunk)
}
```

**Verification**: Read Phone 1 logs and sender code to find root cause

### 2. Add Missing Chunk Retry Mechanism

**Solution**: Implement NACK (negative acknowledgment) system

**Proposed Implementation**:
```kotlin
// After timeout period (e.g., 30 seconds after last chunk):
if (!state.isComplete()) {
    val missing = state.getMissingChunks()
    logger(Log.WARN, "$TAG Broadcast $broadcastId: incomplete, missing ${missing.size} chunks: $missing")
    // Send NACK request to sender for specific chunk indices
    sendNackRequest(broadcastId, missing)
}
```

**Location**: `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt:250+`

### 3. Fix Role UI Updates

**Solution**: Add periodic role checks and connection-triggered updates

**Implementation**:
```kotlin
// In EnhancedMeshFragment.kt
private val roleCheckJob = lifecycleScope.launch {
    while (isActive && meshStarted) {
        delay(5000) // Check every 5 seconds
        updateRolesDisplay()
    }
}

// Trigger on connection status change:
when (newStatus) {
    MeshStateDto.CONNECTED -> {
        updateRolesDisplay()
        roleCheckJob.start()
    }
}
```

### 4. Add Notification System Logging

**Solution**: Add extensive logging to debug notification flow

**Implementation**:
```kotlin
// In EnhancedMeshFragment.kt:309 broadcastListener
broadcastListener = { broadcast ->
    Log.d("EnhancedMeshFragment", "Broadcast received callback: id=${broadcast.broadcastId}, file=${broadcast.fileName}")
    lifecycleScope.launch(Dispatchers.Main) {
        Log.d("EnhancedMeshFragment", "Storing notification for broadcast ${broadcast.broadcastId}")
        receivedBroadcasts.add(0, BroadcastNotification(...))
        Log.d("EnhancedMeshFragment", "Notification stored, count=${receivedBroadcasts.size}")
        (activity as? OrbotActivity)?.updateNotificationBadge(receivedBroadcasts.size)
        Log.d("EnhancedMeshFragment", "Notification badge updated")
    }
}
```

### 5. Implement Timeout and Partial Save

**Solution**: After timeout, save partial transfer with error notification

**Implementation**:
```kotlin
// After 5 minutes of no new chunks:
if (!state.isComplete() && isTimedOut()) {
    logger(Log.WARN, "$TAG Broadcast $broadcastId: timed out with ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks")
    
    // Notify with error
    notifyListeners(BroadcastReceivedDto(
        broadcastId = broadcastId,
        hasError = true,
        errorMessage = "Transfer incomplete: ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks received",
        ...
    ))
}
```

---

## Verification Commands

```bash
# Check Phone 1 sent all chunks:
grep "Broadcasting chunk" phone_test.log | tail -20

# Verify chunk indices 0-6554 all present:
grep -oP 'chunk=\K\d+' phone_test.log | sort -n | uniq | wc -l
# Should output: 6555

# Check for duplicate transmissions:
grep -oP 'chunk=\K\d+' phone_test.log | sort -n | uniq -d | wc -l
# Should output: 0 (no duplicates)

# Find missing chunk indices on Phone 2:
comm -23 <(seq 0 6554) <(grep -oP 'chunk=\K\d+(?=/6555)' phone_test2.log | sort -n | uniq)
```

---

## Conclusion

**Primary Root Cause**: Phone 1 broadcaster did not send all 6,555 unique chunks. Either:
1. Sender skipped 155 chunks (logic error in broadcast loop)
2. Sender sent 155 duplicate chunks instead of missing ones
3. Packet loss occurred at network layer (unlikely given VirtualNode received all 6,555)

**Cascading Effects**:
- Transfer never completed → `isComplete()` false
- File never written → No SharedWithMe folder
- Listener never invoked → No notifications
- User sees "no notifications" toast despite transfer appearance of success

**Next Steps**:
1. Analyze Phone 1 logs to verify sender broadcast logic
2. Implement missing chunk retry/NACK system
3. Add timeout with partial transfer notification
4. Fix role UI update triggers
5. Add comprehensive notification system logging

**Files to Investigate**:
- Phone 1 sender code (broadcast initiation and chunk loop)
- `/Users/dreadstar/workspace/orbot-android/phone_test.log` (sender logs)
- BroadcastMessageHandler outgoing broadcast methods

**Test After Fixes**:
1. Send small file (10 chunks) and verify all received
2. Check logs for "all chunks received, reassembling"
3. Verify SharedWithMe folder created
4. Verify notification appears immediately
5. Test with airplane mode toggle mid-transfer (retry logic)
