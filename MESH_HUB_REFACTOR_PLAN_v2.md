# MESH_HUB Role Implementation - Refactor Plan v2

**Date:** February 6, 2026  
**Status:** Phase 2 - Implementation Planning (CORRECTED WITH ACTUAL CODE)  
**Changes from v1:** All BEFORE code verified against actual files on disk with literal reads

---

## Executive Summary

This plan implements the MESH_HUB role to fix broadcast forwarding for non-concurrent hotspot nodes. The solution uses a **hybrid dual-path architecture** that separates broadcast origination from forwarding.

**Problem:** Non-concurrent hotspots cannot forward broadcasts because they lack the MESH_ROUTER role (which requires AP concurrency hardware capability).

**Solution:** 
1. Add MESH_HUB role for non-concurrent hotspots
2. Modify route() to forward broadcasts if MESH_HUB or MESH_ROUTER
3. (Optional Phase 2) Remove loopback architecture from sendBroadcast()

**Files Modified:** 3 files (minimum), 4 files (full solution)
- MeshRole.kt (add enum value)
- EmergentRoleManager.kt (add role assignment logic) - LARGE FILE
- VirtualNode.kt (modify route() forwarding logic) - LARGE FILE
- BroadcastMessageHandler.kt (optional - remove loopback) - for Phase 2

**Risk Level:** Low (3 files) to Medium (4 files)

---

## 1. File-by-File Implementation Plans

### 1.1 MeshRole.kt (Add MESH_HUB enum)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/MeshRole.kt`

#### Current Implementation (VERIFIED - Lines 13-26)

```kotlin
package com.ustadmobile.meshrabiya.vnet

/**
 * Represents specialized roles nodes can take on in the mesh network
 */
enum class MeshRole {
    MESH_PARTICIPANT,
    STORAGE_NODE,
    COMPUTE_NODE,
    MESH_ROUTER,
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    I2P_GATEWAY,
//    COORDINATOR,
}
```

#### Proposed Implementation

**AFTER (Lines 13-27):**
```kotlin
package com.ustadmobile.meshrabiya.vnet

/**
 * Represents specialized roles nodes can take on in the mesh network
 */
enum class MeshRole {
    MESH_PARTICIPANT,
    STORAGE_NODE,
    COMPUTE_NODE,
    MESH_ROUTER,
    MESH_HUB,  // NEW: Non-concurrent hotspot relay for broadcast forwarding
    TOR_GATEWAY,
    CLEARNET_GATEWAY,
    I2P_GATEWAY,
//    COORDINATOR,
}
```

**Change Summary:**
- Add 1 line: `MESH_HUB,  // NEW: Non-concurrent hotspot relay for broadcast forwarding`
- Insert after `MESH_ROUTER` (line 22)
- File grows from 26 to 27 lines

**Risk:** ✅ MINIMAL - Simple enum addition

---

### 1.2 VirtualNode.kt (Modify route() broadcast forwarding)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/VirtualNode.kt`

**⚠️ LARGE FILE WARNING: 1378 lines - Manual implementation required**

#### Current Implementation (VERIFIED - Lines 790-810)

```kotlin
                    val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
                    if (prev == null) {
                        // PT8: Check TTL before forwarding (prevent infinite loops)
                        if (packet.header.maxHops > 0) {
                            val meshRoles = emergentRoleManager.getCurrentMeshRoles()
                            if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
                                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=MESH_ROUTER, hops remaining: ${packet.header.maxHops})")
                                originatingMessageManager.neighbors().filter {
                                    it.first != fromLastHop && it.first != packet.header.fromAddr
                                }.forEach {
                                    logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
                                    it.second.receivedFromSocket.send(
                                        nextHopAddress = it.second.lastHopRealInetAddr,
                                        nextHopPort = it.second.lastHopRealPort,
                                        virtualPacket = packet,
                                    )
                                }
                            } else {
                                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER, not forwarding")
                            }
                        } else {
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId TTL exhausted (maxHops=0), not forwarding")
                        }
                    }
```

#### Proposed Implementation

**AFTER (Lines 790-810):**
```kotlin
                    val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
                    if (prev == null) {
                        // PT8: Check TTL before forwarding (prevent infinite loops)
                        if (packet.header.maxHops > 0) {
                            val meshRoles = emergentRoleManager.getCurrentMeshRoles()
                            // UPDATED: Allow MESH_HUB nodes to forward broadcasts
                            if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB)) {
                                val roleType = when {
                                    meshRoles.contains(MeshRole.MESH_ROUTER) -> "MESH_ROUTER"
                                    else -> "MESH_HUB"
                                }
                                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=$roleType, hops remaining: ${packet.header.maxHops})")
                                originatingMessageManager.neighbors().filter {
                                    it.first != fromLastHop && it.first != packet.header.fromAddr
                                }.forEach {
                                    logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
                                    it.second.receivedFromSocket.send(
                                        nextHopAddress = it.second.lastHopRealInetAddr,
                                        nextHopPort = it.second.lastHopRealPort,
                                        virtualPacket = packet,
                                    )
                                }
                            } else {
                                logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER or MESH_HUB, not forwarding")
                            }
                        } else {
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId TTL exhausted (maxHops=0), not forwarding")
                        }
                    }
```

**Change Summary:**
- Line 795: Add comment `// UPDATED: Allow MESH_HUB nodes to forward broadcasts`
- Line 796: Change `if (meshRoles.contains(MeshRole.MESH_ROUTER))` to `if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB))`
- Lines 797-800: Add roleType determination
- Line 801: Update log message to include `role=$roleType`
- Line 809: Update else log to mention "MESH_ROUTER or MESH_HUB"

**Risk:** ⚠️ MEDIUM - Changes core routing logic, affects all broadcast traffic

---

### 1.3 EmergentRoleManager.kt (Add MESH_HUB role assignment)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/EmergentRoleManager.kt`

**⚠️ LARGE FILE WARNING: 1355 lines - Manual implementation required**

#### Current Implementation (VERIFIED - Lines 330-352)

```kotlin
        // Use BFS centrality to identify nodes in structurally important positions
        // Nodes with AP+Station concurrency can forward traffic while maintaining connections
        val centralityResult = calculateBFSCentrality()
        val centralityThreshold = 3.0f // Minimum centrality score for router role
        
        if (fitness > 0.6 && centralityResult.centralityScore > centralityThreshold && concurrentApStationSupported) {
            roles.add(MeshRole.MESH_ROUTER)
            safeLog(LogLevel.INFO, "Assigned router role (centrality=${centralityResult.centralityScore}, " +
                "degree=${centralityResult.degree}, reachable=${centralityResult.reachableNodes}, concurrency=true)")
        }
        
        // COORDINATOR ROLE DEPRECATED - Not in canonical design
        // Coordinator role assignment commented out per architectural decision
        // If needed in future, centrality score should be primary factor
        /*
        if (fitness > 0.85 && 
            node.hasStableConnection() && 
            virtualNode.neighbors().size >= 3 &&
            (userPreferences.isEmpty() || MeshRole.COORDINATOR in userPreferences)) {
            roles.add(MeshRole.COORDINATOR)
            safeLog(LogLevel.INFO, "Assigned coordinator role")
        }
        */
```

#### Proposed Implementation

**AFTER (Lines 330-370):**
```kotlin
        // Use BFS centrality to identify nodes in structurally important positions
        // Nodes with AP+Station concurrency can forward traffic while maintaining connections
        val centralityResult = calculateBFSCentrality()
        val centralityThreshold = 3.0f // Minimum centrality score for router role
        
        if (fitness > 0.6 && centralityResult.centralityScore > centralityThreshold && concurrentApStationSupported) {
            roles.add(MeshRole.MESH_ROUTER)
            safeLog(LogLevel.INFO, "Assigned router role (centrality=${centralityResult.centralityScore}, " +
                "degree=${centralityResult.degree}, reachable=${centralityResult.reachableNodes}, concurrency=true)")
        }
        
        // NEW: MESH_HUB role for non-concurrent hotspot nodes
        // Hotspot nodes WITHOUT AP concurrency need forwarding capability to relay broadcasts
        // Assignment criteria:
        // 1. Node started mesh as hotspot (setWifiHotspotEnabled called)
        // 2. Device does NOT have concurrent AP+Station hardware capability
        // MESH_HUB nodes forward broadcasts but cannot bridge mesh segments (no station mode)
        val wifiState = virtualNode.currentNodeState.wifiState
        if (!concurrentApStationSupported && wifiState.hotspotIsStarted) {
            roles.add(MeshRole.MESH_HUB)
            safeLog(LogLevel.INFO, "Assigned MESH_HUB role (hotspot active, no AP concurrency)")
            android.util.Log.i("EmergentRoleManager", "[CALC_TARGET] ✓ Adding MESH_HUB (non-concurrent hotspot)")
        } else {
            safeLog(LogLevel.DEBUG, "MESH_HUB not assigned: concurrency=$concurrentApStationSupported, hotspot=${wifiState.hotspotIsStarted}")
        }
        
        // COORDINATOR ROLE DEPRECATED - Not in canonical design
        // Coordinator role assignment commented out per architectural decision
        // If needed in future, centrality score should be primary factor
        /*
        if (fitness > 0.85 && 
            node.hasStableConnection() && 
            virtualNode.neighbors().size >= 3 &&
            (userPreferences.isEmpty() || MeshRole.COORDINATOR in userPreferences)) {
            roles.add(MeshRole.COORDINATOR)
            safeLog(LogLevel.INFO, "Assigned coordinator role")
        }
        */
```

**Change Summary:**
- Insert 14 lines after MESH_ROUTER assignment (after line 339, before line 340)
- Lines 341-346: Add comment block explaining MESH_HUB role
- Line 347: Get wifiState from virtualNode
- Lines 348-355: MESH_HUB assignment logic with 2 criteria (no stable connection check)

**Logic Flow:**
1. Check `!concurrentApStationSupported` (device lacks AP concurrency)
2. Check `wifiState.hotspotIsStarted` (hotspot currently active)
3. If BOTH true: Add MESH_HUB role with INFO logging
4. If EITHER false: Log DEBUG message explaining why not assigned

**Mutual Exclusivity:**
- MESH_ROUTER requires `concurrentApStationSupported == true`
- MESH_HUB requires `concurrentApStationSupported == false`
- Only one can be assigned at a time

**Risk:** ⚠️ MEDIUM - Adds new role affecting routing decisions

---

### 1.4 BroadcastMessageHandler.kt (Optional - Remove loopback)

**File:** `/Users/dreadstar/workspace/orbot-android/Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/vnet/broadcast/BroadcastMessageHandler.kt`

**⚠️ THIS IS PHASE 2 - NOT REQUIRED FOR BASIC FUNCTIONALITY**

#### Current Implementation (VERIFIED - Lines 145-152)

```kotlin
                    )
                    
                    // Send via VirtualNode
                    virtualNode.route(packet)
                    
                    state.chunksSent++
                    
                    logger(Log.DEBUG, "$TAG Broadcast $broadcastId: sent chunk $chunkIndex/$totalChunks")
```

**Note:** The actual code has been significantly refactored since the original plan was written. The loopback removal is now optional and can be deferred to Phase 2 after testing the core MESH_HUB functionality.

**Decision:** Implement files 1.1-1.3 first (MeshRole.kt, VirtualNode.kt, EmergentRoleManager.kt), test thoroughly, then consider loopback removal as Phase 2 enhancement.

---

## 2. Implementation Roadmap

### Phase 1: Core MESH_HUB Role (3 files)

**Objective:** Enable broadcast forwarding for non-concurrent hotspots

**Files:**
1. MeshRole.kt - Add MESH_HUB enum value
2. VirtualNode.kt - Allow MESH_HUB to forward broadcasts  
3. EmergentRoleManager.kt - Assign MESH_HUB to non-concurrent hotspots

**Testing:**
- Verify MESH_HUB assigned when hotspot active and no concurrency
- Verify broadcasts forward from Phone 1 (MESH_HUB) to Phone 2 (station)
- Verify station-to-station relay via MESH_HUB

**Success Criteria:**
- Non-concurrent hotspot gets MESH_HUB role
- Broadcasts forward correctly through MESH_HUB nodes
- No "not MESH_ROUTER, not forwarding" errors

### Phase 2: Loopback Removal (Optional Enhancement)

**Objective:** Clean up broadcast architecture, remove loopback

**Files:**
1. BroadcastMessageHandler.kt - Direct neighbor sends instead of route()

**Testing:**
- Verify sender doesn't see own broadcasts
- Verify all chunks arrive at recipients
- Verify error handling for failed neighbor sends

**Success Criteria:**
- Sender doesn't receive notification
- All broadcasts complete successfully
- Better logging for send failures

---

## 3. Testing Strategy

### Test Case 1: MESH_HUB Role Assignment

**Setup:**
- Phone 1: Non-concurrent device
- Action: Start mesh as hotspot

**Expected Logs:**
```
EmergentRoleManager: [CALC_TARGET] ✓ Adding MESH_HUB (non-concurrent hotspot)
EmergentRoleManager: [CALC_TARGET] ===== FINAL TARGET ROLES: [MESH_PARTICIPANT, MESH_HUB] =====
```

**Success:** MESH_HUB appears in role list, MESH_ROUTER does NOT

---

### Test Case 2: Broadcast Forwarding (Hub to Station)

**Setup:**
- Phone 1: MESH_HUB (hotspot)
- Phone 2: Station connected to Phone 1

**Action:** Phone 1 broadcasts file

**Expected Logs (Phone 1):**
```
VirtualNode: Broadcast packet [id] not seen before, forwarding to neighbors (role=MESH_HUB, hops remaining: X)
VirtualNode: Forwarding broadcast to neighbor [Phone 2 address]
```

**Expected Logs (Phone 2):**
```
VirtualNode: Received broadcast packet from [Phone 1 address]
BroadcastMessageHandler: Received broadcast chunk 0/XXX
```

**Success:** Phone 2 receives all chunks, file saved successfully

---

### Test Case 3: Station-to-Station Relay

**Setup:**
- Phone 1: MESH_HUB (hotspot)
- Phone 2: Station connected to Phone 1
- Phone 3: Station connected to Phone 1

**Action:** Phone 2 broadcasts file

**Expected:**
- Phone 2 sends to Phone 1 (hub)
- Phone 1 receives and forwards to Phone 3
- Phone 3 receives complete file
- Phone 2 does NOT receive own broadcast (current loopback behavior is acceptable for Phase 1)

**Success:** File reaches Phone 3 through Phone 1 relay

---

## 4. Rollback Plan

### If Tests Fail

**Rollback Order:**
1. Revert EmergentRoleManager.kt (remove MESH_HUB assignment)
2. Revert VirtualNode.kt (remove MESH_HUB from role check)
3. Revert MeshRole.kt (remove MESH_HUB enum)

**Verification:**
```bash
# Build after each revert
./gradlew assembleDebug

# Verify no compilation errors
grep "error:" build_output.log
```

---

## 5. Summary

**Minimum Viable Implementation:** 3 files (Phase 1)
- MeshRole.kt: +1 line
- VirtualNode.kt: ~10 lines changed (LARGE FILE - manual)
- EmergentRoleManager.kt: +14 lines (LARGE FILE - manual)

**Full Implementation:** 4 files (Phase 1 + Phase 2)
- Add BroadcastMessageHandler.kt loopback removal

**Risk Level:** 
- Phase 1: Low-Medium (core functionality, well-tested pattern)
- Phase 2: Medium (architectural change, can be deferred)

**All code in this plan has been verified against actual files on disk as of February 6, 2026.**
